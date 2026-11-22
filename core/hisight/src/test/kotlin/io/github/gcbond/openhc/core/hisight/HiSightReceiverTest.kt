package io.github.gcbond.openhc.core.hisight

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.hisight.control.ControlFrameDecoder
import io.github.gcbond.openhc.core.hisight.control.ControlFraming
import io.github.gcbond.openhc.core.hisight.control.RtspParser
import io.github.gcbond.openhc.core.hisight.crypto.AesCtr
import java.security.SecureRandom
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 会话状态机：PLAY 的时机、输入通道、各种结束方式。手机一侧用合成报文模拟。 */
class HiSightReceiverTest {
    private val key = ByteArray(16) { (0x30 + it).toByte() }

    /** 模拟手机：加密自己的报文，解密并解析会话发出的控制帧。 */
    private inner class Phone {
        val ctr = AesCtr(key)
        val random = SecureRandom()
        var encrypted = false

        fun frame(text: String): ByteArray {
            val plain = ascii(text)
            return ControlFraming.encode(if (encrypted) ctr.seal(plain, random) else plain)
        }

        fun request(method: String, cseq: Int, body: String) =
            frame("$method rtsp://example.invalid/s RTSP/1.0\r\nCSeq: $cseq\r\nContent-Type: text/parameters\r\nContent-Length: ${body.length}\r\n\r\n$body")

        /** 解开会话发往控制连接的帧，返回首行。 */
        fun read(outputs: List<HiSightOutput>): List<String> = outputs.filterIsInstance<HiSightOutput.Send>()
            .filter { it.channel == HiSightChannel.CONTROL }
            .map { send ->
                val decoder = ControlFrameDecoder()
                decoder.append(send.bytes, 0, send.bytes.size)
                val payload = decoder.next()!!
                val plain = if (encrypted) ctr.open(payload) else payload
                RtspParser.parse(plain).firstLine + "#" + RtspParser.parse(plain).cseq
            }
    }

    private fun session(config: HiSightConfig = HiSightConfig(640, 480, 640, 480, 30, 160), sink: AccessUnitSink = AccessUnitSink { }) =
        HiSightReceiver(key, config, SecureRandom(), sink, TimeZone.getTimeZone("UTC"))

    private fun HiSightReceiver.feed(bytes: ByteArray) = feedControl(bytes, 0, bytes.size, 0L)

    /** 推进到 SETUP 应答之后，返回这一步的输出。 */
    private fun negotiate(s: HiSightReceiver, phone: Phone, transport: String, uibc: String? = null, device: String? = null): List<HiSightOutput> {
        s.start()
        s.feed(phone.frame("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n") +
            phone.frame("ANNOUNCE * RTSP/1.0\r\nCSeq: 8\r\nContent-Type: text/parameters\r\nContent-Length: 55\r\n\r\nencrypt_description: encrypt_list=aes128ctr;version=1\r\n"))
        phone.encrypted = true
        assertEquals(listOf("RTSP/1.0 200 OK#9", "OPTIONS * RTSP/1.0#2"), phone.read(s.feed(phone.frame("OPTIONS * RTSP/1.0\r\nCSeq: 9\r\n\r\n"))))
        s.feed(phone.frame("RTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\n"))
        var m4 = "his_video_formats: codecs 1;width 640;height 480;fps 30\r\n"
        if (uibc != null) m4 += "his_uibc_capability: $uibc\r\n"
        if (device != null) m4 += "his_device_type: $device\r\n"
        assertEquals(listOf("RTSP/1.0 200 OK#11"), phone.read(s.feed(phone.request("SET_PARAMETER", 11, m4))))
        assertEquals(listOf("RTSP/1.0 200 OK#12", "SETUP * RTSP/1.0#3"),
            phone.read(s.feed(phone.request("SET_PARAMETER", 12, "his_trigger_method: SETUP\r\n"))))
        return s.feed(phone.frame("RTSP/1.0 200 OK\r\nCSeq: 3\r\nTransport: $transport\r\n\r\n"))
    }

    @Test
    fun playWaitsForBothConnectionsInEitherOrder() {
        for (videoFirst in listOf(true, false)) {
            val phone = Phone()
            val s = session()
            val setup = negotiate(s, phone, "RTP/AVP/TCP;unicast;server_port=5000;remotectl_port=5001")
            assertEquals(listOf(5000, 5001), setup.filterIsInstance<HiSightOutput.Connect>().map { it.port })
            val first = if (videoFirst) s.onVideoConnected() else s.onRemoteControlConnected()
            assertTrue(first.isEmpty())
            val second = if (videoFirst) s.onRemoteControlConnected() else s.onVideoConnected()
            assertEquals(listOf("PLAY * RTSP/1.0#4"), phone.read(second))
            val played = s.feed(phone.frame("RTSP/1.0 200 OK\r\nCSeq: 4\r\n\r\n"))
            assertEquals(listOf<HiSightOutput>(HiSightOutput.PlayAccepted), played)
            // 视频连接正常结束：发 TEARDOWN 并结束。
            val end = s.onVideoClosed()
            assertEquals(listOf("TEARDOWN  RTSP/1.0#5"), phone.read(end))
            assertEquals(HiSightEndReason.VIDEO_CLOSED, (end.last() as HiSightOutput.Ended).reason)
        }
    }

    @Test
    fun withoutRemotePortPlayFollowsVideo() {
        val phone = Phone()
        val s = session()
        val setup = negotiate(s, phone, "RTP/AVP/UDP;unicast;server_port=5000;rtcp_port=-1")
        assertEquals(listOf(HiSightChannel.VIDEO), setup.filterIsInstance<HiSightOutput.Connect>().map { it.channel })
        assertFailsWith<IllegalStateException> { s.onRemoteControlConnected() }
        assertEquals(listOf("PLAY * RTSP/1.0#4"), phone.read(s.onVideoConnected()))
        assertFailsWith<IllegalStateException> { s.onVideoConnected() }
    }

    @Test
    fun connectBeforeSetupIsAProgrammingError() {
        val s = session()
        s.start()
        assertFailsWith<IllegalStateException> { s.onVideoConnected() }
        assertFailsWith<IllegalStateException> { s.start() }
        assertFailsWith<IllegalStateException> { session().feedControl(ByteArray(1), 0, 1, 0) }
    }

    @Test
    fun inputsFlowOnlyWhenRemoteIsOpenAndAreReleasedOnShutdown() {
        val phone = Phone()
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true, keyInput = true)
        val s = session(config)
        val events = "left_key_up, left_key_down, left_move, left_point_up, left_point_down"
        val setup = negotiate(s, phone, "RTP/AVP/TCP;unicast;server_port=5000;remotectl_port=5001",
            uibc = "input_category_list=GENERIC;generic_cap_list=$events;", device = "source_device_type 7;device_type 4;subtype 0;")
        val negotiated = setup.filterIsInstance<HiSightOutput.StreamNegotiated>().single()
        assertTrue(negotiated.touchInput && negotiated.keyInput && !negotiated.textInput)

        val down = HiSightInput.Touch(HiSightInput.Touch.Action.DOWN, listOf(HiSightInput.Point(0, 10, 10)), 640, 480)
        assertTrue(s.sendInput(down).isEmpty(), "回控未连上时输入被丢弃")
        s.onVideoConnected()
        s.onRemoteControlConnected()
        val sent = s.sendInput(down)
        assertEquals(HiSightChannel.REMOTE_CONTROL, (sent.single() as HiSightOutput.Send).channel)
        s.sendInput(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 29, 0))
        assertFailsWith<IllegalStateException> { s.sendInput(down) }
        assertFailsWith<IllegalArgumentException> { s.sendInput(HiSightInput.Text("A")) }

        val end = s.shutdown()
        val remote = end.filterIsInstance<HiSightOutput.Send>().filter { it.channel == HiSightChannel.REMOTE_CONTROL }
        assertEquals(2, remote.size, "抬起触点和按键")
        assertEquals(listOf("TEARDOWN  RTSP/1.0#5"), phone.read(end))
        assertEquals(HiSightEndReason.LOCAL_SHUTDOWN, (end.last() as HiSightOutput.Ended).reason)
        assertTrue(s.sendInput(down).isEmpty())
        assertTrue(s.shutdown().isEmpty())
    }

    @Test
    fun inputsRequireRemotePortWhenAdvertised() {
        val phone = Phone()
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true)
        val s = session(config)
        assertFailsWith<MalformedMessageException> {
            negotiate(s, phone, "RTP/AVP/TCP;unicast;server_port=5000",
                uibc = "input_category_list=GENERIC;generic_cap_list=left_point_down, left_point_up, left_move;")
        }
        assertFailsWith<IllegalStateException> { session().sendInput(HiSightInput.Text("A")) }
    }

    @Test
    fun remoteClosedEndsSessionWithTeardown() {
        val phone = Phone()
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true)
        val s = session(config)
        negotiate(s, phone, "RTP/AVP/TCP;unicast;server_port=5000;remotectl_port=5001",
            uibc = "input_category_list=GENERIC;generic_cap_list=left_point_down, left_point_up, left_move;")
        s.onVideoConnected()
        s.onRemoteControlConnected()
        s.sendInput(HiSightInput.Touch(HiSightInput.Touch.Action.DOWN, listOf(HiSightInput.Point(0, 1, 1)), 640, 480))
        assertFailsWith<MalformedMessageException> { s.feedRemoteControl(ByteArray(3), 0, 3) }
        val end = s.onRemoteControlClosed()
        assertTrue(end.filterIsInstance<HiSightOutput.Send>().none { it.channel == HiSightChannel.REMOTE_CONTROL })
        assertEquals(listOf("TEARDOWN  RTSP/1.0#5"), phone.read(end))
        assertEquals(HiSightEndReason.REMOTE_CONTROL_CLOSED, (end.last() as HiSightOutput.Ended).reason)
    }

    @Test
    fun controlClosedEndsWithoutTeardownUnlessHalfFrame() {
        val phone = Phone()
        val s = session()
        negotiate(s, phone, "RTP/AVP/TCP;unicast;server_port=5000")
        val end = s.onControlClosed()
        assertTrue(phone.read(end).isEmpty())
        assertEquals(HiSightEndReason.CONTROL_CLOSED, (end.last() as HiSightOutput.Ended).reason)

        val phone2 = Phone()
        val s2 = session()
        negotiate(s2, phone2, "RTP/AVP/TCP;unicast;server_port=5000")
        s2.feed(byteArrayOf(0, 0))
        assertFailsWith<MalformedMessageException> { s2.onControlClosed() }
        assertEquals(listOf("TEARDOWN  RTSP/1.0#4"), phone2.read(s2.shutdown()))
    }

    @Test
    fun videoFramesReachSinkAndVideoErrorsSurface() {
        val phone = Phone()
        val frames = ArrayList<ByteArray>()
        val gaps = ArrayList<VideoGap?>()
        val s = session(sink = AccessUnitSink { frames += it.copyBytes(); gaps += it.gapBefore })
        negotiate(s, phone, "RTP/AVP/TCP;unicast;server_port=5000")
        val es = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val sealed = AesCtr(key).seal(es, SecureRandom())
        val pes = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x84.toByte(), 0x80.toByte(), 5, 0x21, 0, 1, 0, 1) + sealed
        val rtp = byteArrayOf(0x80.toByte(), 0xE0.toByte(), 0, 1, 0, 0, 0, 0, 0, 0, 0, 1) + pes
        val packet = byteArrayOf(0, 0, 0, rtp.size.toByte()) + rtp
        s.feedVideo(packet, 0, packet.size)
        assertEquals(1, frames.size)
        assertTrue(es.contentEquals(frames[0]))
        // 同一序号再来一次 = 重复包：只算断档（手机拥塞时会整批丢帧，序号会跳），这一包是完整的一帧，照交并带上断档情况。
        s.feedVideo(packet, 0, packet.size)
        assertEquals(2, frames.size)
        assertEquals(null, gaps[0])
        assertEquals(1, gaps[1]!!.receivedSequence)
        // SSRC 变了才是真错：视频流失败，错误报出来。
        val other = rtp.copyOf().also { it[3] = 2; it[11] = 2 }
        val bad = byteArrayOf(0, 0, 0, other.size.toByte()) + other
        assertFailsWith<MalformedMessageException> { s.feedVideo(bad, 0, bad.size) }
        assertFailsWith<MalformedMessageException> { s.onVideoClosed() }
        assertEquals(listOf("TEARDOWN  RTSP/1.0#4"), phone.read(s.shutdown()))
    }

    @Test
    fun outputsBeforeAnErrorAreKeptForShutdown() {
        val phone = Phone()
        val s = session()
        s.start()
        val announce = phone.frame("ANNOUNCE * RTSP/1.0\r\nCSeq: 8\r\nContent-Type: text/parameters\r\nContent-Length: 55\r\n\r\nencrypt_description: encrypt_list=aes128ctr;version=1\r\n")
        assertFailsWith<MalformedMessageException> { s.feed(announce + ControlFraming.encode(ByteArray(10))) }
        assertTrue(s.feed(phone.frame("x")).isEmpty(), "失败后不再处理")
        val end = s.shutdown()
        val control = end.filterIsInstance<HiSightOutput.Send>()
        assertEquals(2, control.size)
        assertEquals(listOf("RTSP/1.0 200 OK#8"), phone.read(listOf(control[0])))
        phone.encrypted = true
        assertEquals(listOf("TEARDOWN  RTSP/1.0#2"), phone.read(listOf(control[1])))
    }

    @Test
    fun concurrentGroupsDoNotDeadlock() {
        val phone = Phone()
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true)
        val s = session(config)
        negotiate(s, phone, "RTP/AVP/TCP;unicast;server_port=5000;remotectl_port=5001",
            uibc = "input_category_list=GENERIC;generic_cap_list=left_point_down, left_point_up, left_move;")
        s.onVideoConnected()
        s.onRemoteControlConnected()
        val input = Thread {
            repeat(2000) {
                val p = listOf(HiSightInput.Point(0, it % 600, it % 400))
                s.sendInput(HiSightInput.Touch(HiSightInput.Touch.Action.DOWN, p, 640, 480))
                s.sendInput(HiSightInput.Touch(HiSightInput.Touch.Action.UP, p, 640, 480))
            }
        }
        val control = Thread {
            repeat(200) { s.feed(phone.request("GET_PARAMETER", 100 + it, "")) }
        }
        input.start(); control.start()
        input.join(10_000); control.join(10_000)
        assertTrue(!input.isAlive && !control.isAlive)
        s.shutdown()
    }

    @Test
    fun mediaKeysWorkWithoutSource7() {
        val phone = Phone()
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true, mediaKeyInput = true)
        val s = session(config)
        val events = "left_point_down, left_point_up, left_move, left_key_down, left_key_up"
        val setup = negotiate(s, phone, "RTP/AVP/TCP;unicast;server_port=5000;remotectl_port=5001",
            uibc = "input_category_list=GENERIC;generic_cap_list=$events;", device = "device_type 4;subtype 0;")
        val negotiated = setup.filterIsInstance<HiSightOutput.StreamNegotiated>().single()
        assertTrue(negotiated.touchInput && negotiated.keyInput)
        s.onVideoConnected()
        s.onRemoteControlConnected()

        fun plainOf(input: HiSightInput): String {
            val send = s.sendInput(input).single() as HiSightOutput.Send
            assertEquals(HiSightChannel.REMOTE_CONTROL, send.channel)
            return AesCtr(key).open(send.bytes, 4, send.bytes.size - 4).toHex()
        }
        assertEquals("ffffffffffff030009000057000000000000", plainOf(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 87, 0)))
        assertEquals("ffffffffffff040009000057000000000000", plainOf(HiSightInput.Key(HiSightInput.Key.Action.UP, 87, 0)))
        // 手机没选 7：只能发基础键。
        assertFailsWith<IllegalArgumentException> { s.sendInput(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 29, 0)) }
        s.shutdown()
    }

    @Test
    fun mediaKeysNotChosenDoNotStopProjection() {
        val phone = Phone()
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true, mediaKeyInput = true)
        val s = session(config)
        val setup = negotiate(s, phone, "RTP/AVP/TCP;unicast;server_port=5000;remotectl_port=5001",
            uibc = "input_category_list=GENERIC;generic_cap_list=left_point_down, left_point_up, left_move;")
        val negotiated = setup.filterIsInstance<HiSightOutput.StreamNegotiated>().single()
        assertTrue(negotiated.touchInput && !negotiated.keyInput)
        s.onVideoConnected()
        assertEquals(listOf("PLAY * RTSP/1.0#4"), phone.read(s.onRemoteControlConnected()))
        assertFailsWith<IllegalArgumentException> { s.sendInput(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 87, 0)) }
        s.shutdown()
    }

    // ---------------- 加密选择：各家手机系统的写法差异 ----------------

    /** 用给定的正文拼一条手机的 ANNOUNCE（Content-Length 按实际字节算），分帧后交给调用方。 */
    private fun announceFrame(body: ByteArray): ByteArray {
        val head = "ANNOUNCE * RTSP/1.0\r\nCSeq: 8\r\nContent-Type: text/parameters\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        val plain = head.toByteArray(Charsets.US_ASCII) + body
        return ControlFraming.encode(plain)
    }

    /** 能认出加密选择就算通过：会话明文回 200。 */
    private fun assertAnnounceAccepted(body: String) {
        val phone = Phone()
        val s = session()
        s.start()
        assertEquals(
            listOf("RTSP/1.0 200 OK#8"),
            phone.read(s.feed(announceFrame(body.toByteArray(Charsets.US_ASCII)))),
            "应该接受：${body.replace("\r", "\\r").replace("\n", "\\n")}",
        )
    }

    private fun assertAnnounceRejected(body: ByteArray): String {
        val s = session()
        s.start()
        return assertFailsWith<MalformedMessageException> { s.feed(announceFrame(body)) }.message ?: ""
    }

    @Test
    fun announceAcceptsTheKnownSingleLineForm() {
        assertAnnounceAccepted("encrypt_description: encrypt_list=aes128ctr;version=1\r\n")
    }

    /** 鸿蒙 4.2 这类写法：行尾不是 CRLF、前后多空行、附带别的字段、列表里还有别的算法。 */
    @Test
    fun announceAcceptsOtherLineEndingsAndExtraFields() {
        // 裸 LF
        assertAnnounceAccepted("encrypt_description: encrypt_list=aes128ctr;version=1\n")
        // 裸 CR
        assertAnnounceAccepted("encrypt_description: encrypt_list=aes128ctr;version=1\r")
        // 末尾多个空行
        assertAnnounceAccepted("encrypt_description: encrypt_list=aes128ctr;version=1\r\n\r\n\r\n")
        // 前后有别的行
        assertAnnounceAccepted("x-vendor: 1\r\nencrypt_description: encrypt_list=aes128ctr;version=1\r\nx-tail: 2\r\n")
        // 字段顺序不同、带空格、带别的字段
        assertAnnounceAccepted("encrypt_description: version=1; encrypt_list=aes128ctr; extra=abc\r\n")
        // 列表里有多个算法
        assertAnnounceAccepted("encrypt_description: encrypt_list=aes128ctr,aes256ctr;version=1\r\n")
        // 版本写成 1.0，算法名大写
        assertAnnounceAccepted("encrypt_description: encrypt_list=AES128CTR;version=1.0\r\n")
        // 没有结尾换行
        assertAnnounceAccepted("encrypt_description: encrypt_list=aes128ctr;version=1")
        // 省掉 encrypt_description 前缀，直接给字段
        assertAnnounceAccepted("encrypt_list=aes128ctr;version=1\r\n")
        // 值带引号
        assertAnnounceAccepted("encrypt_description: encrypt_list=\"aes128ctr\";version='1'\r\n")
    }

    /** 认不出时必须说清是哪里不对，并把正文摘要带出来，方便照着现场排查。 */
    @Test
    fun announceRejectsUnknownFormsWithTheBodyExcerpt() {
        val missing = assertAnnounceRejected("x-vendor: 1\r\nx-other: 2\r\n".toByteArray(Charsets.US_ASCII))
        assertTrue("encrypt_list" in missing, missing)
        assertTrue("x-vendor" in missing, missing)

        val noAlgorithm = assertAnnounceRejected("encrypt_description: encrypt_list=aes256ctr;version=1\r\n".toByteArray(Charsets.US_ASCII))
        assertTrue("aes128ctr" in noAlgorithm, noAlgorithm)

        val noVersion = assertAnnounceRejected("encrypt_description: encrypt_list=aes128ctr\r\n".toByteArray(Charsets.US_ASCII))
        assertTrue("version" in noVersion, noVersion)

        val badVersion = assertAnnounceRejected("encrypt_description: encrypt_list=aes128ctr;version=2\r\n".toByteArray(Charsets.US_ASCII))
        assertTrue("version 1" in badVersion, badVersion)
    }

    @Test
    fun announceRejectsDuplicatesAndNonAscii() {
        val body = "encrypt_description: encrypt_list=aes128ctr;version=1\r\n".toByteArray(Charsets.US_ASCII)
        val s = session()
        s.start()
        s.feed(announceFrame(body))
        assertFailsWith<MalformedMessageException> { s.feed(announceFrame(body)) }

        val nonAscii = session()
        nonAscii.start()
        assertFailsWith<MalformedMessageException> {
            nonAscii.feed(announceFrame(body + byteArrayOf(0xFF.toByte())))
        }
    }

    /** 鸿蒙 4.2 不先发 OPTIONS 就查能力：加密协商完之后直接回能力，不要求先过 OPTIONS。 */
    @Test
    fun capabilityQueryWorksWithoutPriorOptions() {
        val phone = Phone()
        val s = session()
        s.start()
        s.feed(announceFrame("encrypt_description: encrypt_list=aes128ctr;version=1\r\n".toByteArray(Charsets.US_ASCII)))
        phone.encrypted = true
        val body = "his_version\r\nhis_video_formats\r\n"
        val reply = phone.read(s.feed(phone.request("GET_PARAMETER", 9, body)))
        assertEquals(listOf("RTSP/1.0 200 OK#9"), reply)
    }

    /** 加密协商之前的能力请求仍然拒绝，提示里要带上「已收到哪些请求」，便于不抓包也能定位。 */
    @Test
    fun capabilityQueryBeforeAnnounceIsRejectedWithTrace() {
        val phone = Phone()
        val s = session()
        s.start()
        val message = assertFailsWith<MalformedMessageException> {
            s.feed(phone.request("GET_PARAMETER", 9, "his_version\r\n"))
        }.message ?: ""
        assertTrue("加密协商" in message, message)
        assertTrue("GET_PARAMETER" in message, message)
    }

    /**
     * 带 `module_id` 的 SET_PARAMETER 是手机发给某个子模块的（实测鸿蒙 4.2 的「智慧聆听」用 1001），
     * 不是能力协商：回 200 让它继续，但不当作能力选择、也不能因此断开投屏。
     */
    @Test
    fun moduleRoutedSetParameterIsAnsweredButNotTreatedAsCapabilitySelection() {
        val phone = Phone()
        val s = session(HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true))
        s.start()
        s.feed(announceFrame("encrypt_description: encrypt_list=aes128ctr;version=1\r\n".toByteArray(Charsets.US_ASCII)))
        phone.encrypted = true

        // 先正常完成一次能力选择，确认后面的 module 请求不会污染它。
        val uibc = "input_category_list=GENERIC;generic_cap_list=left_point_down, left_point_up, left_move;"
        s.feed(phone.request("SET_PARAMETER", 11, "his_video_formats: codecs 1;width 640;height 480;fps 30\r\nhis_uibc_capability: $uibc\r\n"))

        // 智慧聆听的子模块请求：只回 200，不报错。
        val routed = phone.read(
            s.feed(phone.request("SET_PARAMETER", 12, "module_id: 1001\r\nhis_continuous_dialog: 1\r\n")),
        )
        assertEquals(listOf("RTSP/1.0 200 OK#12"), routed)

        // 能力选择仍然有效：SETUP 触发照常走通。
        val setup = phone.read(s.feed(phone.request("SET_PARAMETER", 13, "his_trigger_method: SETUP\r\n")))
        assertEquals(listOf("RTSP/1.0 200 OK#13", "SETUP * RTSP/1.0#2"), setup)
        s.shutdown()
    }

    /** 没有 `module_id` 的未知字段仍然拒绝：那才是真拆歪了。 */
    @Test
    fun unknownFieldWithoutModuleIdIsStillRejected() {
        val phone = Phone()
        val s = session()
        s.start()
        s.feed(announceFrame("encrypt_description: encrypt_list=aes128ctr;version=1\r\n".toByteArray(Charsets.US_ASCII)))
        phone.encrypted = true
        val message = assertFailsWith<MalformedMessageException> {
            s.feed(phone.request("SET_PARAMETER", 11, "totally_unknown_field: 1\r\n"))
        }.message ?: ""
        assertTrue("名字未知" in message, message)
    }
}
