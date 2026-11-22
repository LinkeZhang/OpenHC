package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.dmsdp.audio.AudioCodec
import io.github.gcbond.openhc.core.dmsdp.audio.AudioEndReason
import io.github.gcbond.openhc.core.dmsdp.audio.AudioEvent
import io.github.gcbond.openhc.core.dmsdp.audio.AudioPacket
import io.github.gcbond.openhc.core.dmsdp.audio.AudioRoute
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamInfo
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.AudioSynthetic
import io.github.gcbond.openhc.core.dmsdp.audio.AudioTransport
import io.github.gcbond.openhc.core.dmsdp.audio.CarAudioFocus
import io.github.gcbond.openhc.core.dmsdp.audio.FrameKind
import io.github.gcbond.openhc.core.dmsdp.audio.PcmVerdict
import io.github.gcbond.openhc.core.dmsdp.audio.PhoneAudioFocus
import io.github.gcbond.openhc.core.dmsdp.audio.PhoneCallPhase
import io.github.gcbond.openhc.core.dmsdp.audio.PhoneRef
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 扬声器服务：声明（2102 的几种形状、按手机退回 AAC）、数据会话的触发与 200、SETUP、手机应答、拆除、替换、格式不对的触发、
 * 音频焦点消息、车机焦点变化发给手机、分流后按路分通道、PCM 能不能出声的判定，以及音频数据经回调交出。
 */
class SpeakerSessionTest {

    private val phone = Phone()

    /** 记下音频回调。 */
    private class AudioRecorder : AudioStreamListener {
        val events = ArrayList<AudioEvent>()
        val raw = ByteArrayOutputStream()
        val packets = ArrayList<ByteArray>()
        val routes = ArrayList<Pair<Int, AudioRoute>>()
        var throwOnEvent = false

        override fun onAudioEvent(event: AudioEvent) {
            events += event
            if (throwOnEvent) throw IllegalStateException("测试抛出")
        }

        override fun onAudioRawData(stream: AudioStreamInfo, data: ByteArray, offset: Int, length: Int) {
            raw.write(data, offset, length)
        }

        override fun onAudioPacket(packet: AudioPacket) {
            packets += packet.copyPayload()
            routes += packet.bus to packet.route
            continuous += packet.continuous
        }

        val continuous = ArrayList<Boolean>()

        inline fun <reified T : AudioEvent> only(): T = events.filterIsInstance<T>().single()
    }

    /** 手机的设备信息 101（合成：deviceId 是 [Synthetic.PEER_ID]，名字「合成手机」）。 */
    private fun DmsdpSession.phoneDeviceInfo() = feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex(CrossVectors.PHONE_101)), 0)

    /** 手机请求扬声器服务（104，掩码 4），返回车机回的 102 里 2102 的值（已去掉一层 JSON 字符串转义）。 */
    private fun DmsdpSession.speakerProfile(): String {
        val reply = feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex("e8") + hex("00000004")), 0)
        val record = phone.decrypt(reply.sends(DmsdpChannel.CONTROL).single().let { it.copyOfRange(3, it.size) })
        val json = String(record, 12, record.size - 12, Charsets.UTF_8)
        val start = json.indexOf("\"2102\":\"") + 8
        val end = json.indexOf("\"},\"status\"")
        return json.substring(start, end).replace("\\\"", "\"")
    }

    /** 手机的音频焦点消息（module 4）。 */
    private fun focus(type: Int, data: String): ByteArray = phone.encryptedRecord(4, type, data.toByteArray())

    private fun speakerSession(
        speaker: DmsdpConfig.Speaker = DmsdpConfig.Speaker(),
        audio: AudioRecorder = AudioRecorder(),
    ): DmsdpSession = encryptedSession(config = Synthetic.config(speaker = speaker), audio = audio).first

    /** 手机的数据会话触发：头部名是小写，正文是触发字段。 */
    private fun trigger(method: String, sessionId: String = "77", cseq: String = "5", simple: String = "1", extraHeaders: String = ""): ByteArray {
        val body = "msdp_trigger_method: $method\r\n"
        return phone.encryptedText(
            "SET_PARAMETER * DMSDP/1.0\r\ncseq: $cseq\r\nserviceid: speaker1\r\nservicetype: 3\r\n" +
                "datasessionid: $sessionId\r\nsimple: $simple\r\nparaaudio: SampleRate=48000;Format=16;ChannelNum=2;Codec=0\r\n" +
                extraHeaders + "content-length: ${body.length}\r\n\r\n" + body,
        )
    }

    private fun text(send: DmsdpOutput): String = String(phone.openControlFrame(assertIs<DmsdpAction.Send>(send).bytes))

    /** 走到 SETUP 已发出（数据端口 40100），返回会话。手机先报设备信息、请求扬声器服务。 */
    private fun toSetupSent(audio: AudioRecorder, speaker: DmsdpConfig.Speaker = DmsdpConfig.Speaker()): DmsdpSession {
        val session = speakerSession(speaker, audio)
        session.phoneDeviceInfo()
        session.speakerProfile()
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), 1_000)
        session.audioListenersReady(40100, 1_050)
        return session
    }

    @Test
    fun speakerServiceIsAnsweredOnlyForItsMaskBit() {
        val session = speakerSession()
        session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex(CrossVectors.PHONE_101)), 0)

        val both = session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex("e8") + hex("0000000c")), 0)
        assertEquals(listOf("display1", "speaker1"), (both.events().single() as DmsdpEvent.ServicesRequested).answeredServiceIds)
        val records = both.sends(DmsdpChannel.CONTROL).map { phone.decrypt(it.copyOfRange(3, it.size)) }
        assertEquals(2, records.size, "显示在前、扬声器在后")
        val speaker = records[1]
        // 记录头 11 字节、EA(102) 1 字节，后面是 JSON。
        assertEquals(0xE6, speaker[11].toInt() and 0xFF)
        // 默认（AAC + 多声道分流）：2102 是字符串，里面的引号带转义。
        assertEquals(
            "{\"deviceId\":\"${Synthetic.SELF_ID}\",\"deviceName\":\"测试车机 Ünï\",\"serviceType\":4,\"serviceId\":\"speaker1\"," +
                "\"properties\":{\"2005\":\"\",\"2004\":\"\",\"2003\":\"\",\"2102\":\"{\\\"codecs\\\":[],\\\"currentVolume\\\":" +
                "{\\\"a\\\":false},\\\"extendInfo\\\":{\\\"MultiAudioBusCap\\\":\\\"true\\\",\\\"MultiAudioBusPolicy\\\":" +
                "\\\"8:4096:1\\\"},\\\"profiles\\\":[],\\\"soundEffect\\\":0}\"},\"status\":{}}",
            String(speaker, 12, speaker.size - 12, Charsets.UTF_8),
        )

        val displayOnly = session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex("e8") + hex("00000008")), 0)
        assertEquals(listOf("display1"), (displayOnly.events().single() as DmsdpEvent.ServicesRequested).answeredServiceIds)
        val speakerOnly = session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex("e8") + hex("00000004")), 0)
        assertEquals(listOf("speaker1"), (speakerOnly.events().single() as DmsdpEvent.ServicesRequested).answeredServiceIds)
    }

    /** 2102 的四种形状逐字节核对：AAC / PCM × 多音频总线开 / 关。键名和顺序都是线上格式的一部分。 */
    @Test
    fun speakerProfileShapesAreExact() {
        val multiBus = "{\"MultiAudioBusCap\":\"true\",\"MultiAudioBusPolicy\":\"8:4096:1\"}"
        assertEquals(
            "{\"codecs\":[],\"currentVolume\":{\"a\":false},\"extendInfo\":$multiBus,\"profiles\":[],\"soundEffect\":0}",
            DmsdpConfig.Speaker(codec = AudioCodec.AAC).audioProfile,
        )
        assertEquals(
            "{\"codecs\":[1],\"currentVolume\":{\"a\":false},\"extendInfo\":$multiBus," +
                "\"profiles\":[{\"sampleRate\":48000,\"channel\":12,\"format\":1}],\"soundEffect\":0}",
            DmsdpConfig.Speaker(codec = AudioCodec.PCM).audioProfile,
        )
        assertEquals(
            "{\"codecs\":[],\"currentVolume\":{\"a\":false},\"extendInfo\":{},\"profiles\":[],\"soundEffect\":0}",
            DmsdpConfig.Speaker(codec = AudioCodec.AAC, multiBus = false).audioProfile,
            "关掉多声道分流就是原来实测接受的空 extendInfo",
        )
        assertEquals(
            "{\"codecs\":[1],\"currentVolume\":{\"a\":false},\"extendInfo\":{}," +
                "\"profiles\":[{\"sampleRate\":48000,\"channel\":12,\"format\":1}],\"soundEffect\":0}",
            DmsdpConfig.Speaker(codec = AudioCodec.PCM, multiBus = false).audioProfile,
        )
        // 字节层面：多总线那段就是这些 UTF-8 字节，没有空格。
        assertEquals(
            "7b224d756c7469417564696f427573436170223a2274727565222c224d756c7469417564696f427573506f6c696379223a22383a343039363a31227d",
            multiBus.toByteArray(Charsets.UTF_8).toHex(),
        )
    }

    /** 选 PCM 时服务声明里的 2102 是 PCM 形状，SETUP 写 Codec: 0。 */
    @Test
    fun pcmChoiceDeclaresPcmCapabilityAndAsksForCodecZero() {
        val audio = AudioRecorder()
        val session = speakerSession(speaker = DmsdpConfig.Speaker(codec = AudioCodec.PCM), audio = audio)
        session.phoneDeviceInfo()
        assertEquals(DmsdpConfig.Speaker(codec = AudioCodec.PCM).audioProfile, session.speakerProfile())
        val plan = audio.only<AudioEvent.SpeakerDeclared>().plan
        assertEquals(AudioCodec.PCM, plan.codec)
        assertFalse(plan.fallback)
        assertEquals(PhoneRef.keyOf(Synthetic.PEER_ID), plan.phone?.key)
        assertEquals("合成手机", plan.phone?.name)
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), 1_000)
        val setup = text(session.audioListenersReady(40100, 1_050).single())
        assertTrue("Fillp: 0\r\nTCP: 1\r\nCodec: 0\r\n" in setup, setup)
        assertEquals(AudioCodec.PCM, audio.only<AudioEvent.Requested>().stream.codec)
    }

    /** 记着「PCM 不出声」的手机这次改声明 AAC（2102 和 SETUP 一致）；别的手机照常 PCM。 */
    @Test
    fun phonesOnTheAacListAreDeclaredAac() {
        val key = PhoneRef.keyOf(Synthetic.PEER_ID)
        assertEquals(16, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })

        val listed = AudioRecorder()
        val session = speakerSession(speaker = DmsdpConfig.Speaker(codec = AudioCodec.PCM, aacPhones = setOf(key)), audio = listed)
        session.phoneDeviceInfo()
        assertEquals(DmsdpConfig.Speaker(codec = AudioCodec.AAC).audioProfile, session.speakerProfile())
        val plan = listed.only<AudioEvent.SpeakerDeclared>().plan
        assertEquals(AudioCodec.AAC, plan.codec)
        assertTrue(plan.fallback)
        assertTrue("改声明 AAC" in plan.toString() && key.take(6) in plan.toString() && key !in plan.toString(), plan.toString())
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), 1_000)
        assertTrue("Codec: 1\r\n" in text(session.audioListenersReady(40100, 1_050).single()), "SETUP 跟着声明走")

        // 名单里是别的手机：照常 PCM。
        val other = AudioRecorder()
        val second = speakerSession(speaker = DmsdpConfig.Speaker(codec = AudioCodec.PCM, aacPhones = setOf("00112233aabbccdd")), audio = other)
        second.phoneDeviceInfo()
        assertEquals(DmsdpConfig.Speaker(codec = AudioCodec.PCM).audioProfile, second.speakerProfile())
        assertFalse(other.only<AudioEvent.SpeakerDeclared>().plan.fallback)

        // 设备信息没到就要声明：认不出手机，按设置的 PCM，声明定下后不再变。
        val unknown = AudioRecorder()
        val third = speakerSession(speaker = DmsdpConfig.Speaker(codec = AudioCodec.PCM, aacPhones = setOf(key)), audio = unknown)
        assertEquals(DmsdpConfig.Speaker(codec = AudioCodec.PCM).audioProfile, third.speakerProfile())
        third.phoneDeviceInfo()
        assertEquals(DmsdpConfig.Speaker(codec = AudioCodec.PCM).audioProfile, third.speakerProfile(), "同一连接里声明不变")
        assertNull(unknown.only<AudioEvent.SpeakerDeclared>().plan.phone)
    }

    @Test
    fun optionsTriggerIsAnsweredThenSetupGoesOutWhenPortsAreReady() {
        val audio = AudioRecorder()
        val session = speakerSession(audio = audio)
        val trigger = session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), 1_000)
        assertEquals(2, trigger.size, "先 200，再要求开端口：$trigger")
        assertEquals(
            "DMSDP/1.0 200 OK\r\nServiceID: speaker1\r\nServiceType: 3\r\nDataSessionID: 77\r\nCSeq: 5\r\n\r\n",
            text(trigger[0]),
        )
        assertIs<DmsdpAction.OpenAudioListeners>(trigger[1])
        val stream = audio.only<AudioEvent.Requested>().stream
        assertEquals("77", stream.dataSessionId)
        assertEquals(listOf(48_000, 2, 16), listOf(stream.sampleRate, stream.channels, stream.bitsPerSample))
        assertEquals(0, stream.requestedCodec)
        assertEquals(AudioCodec.AAC, stream.codec, "默认 AAC")

        val setup = session.audioListenersReady(40100, 1_050)
        assertEquals(
            "SETUP * DMSDP/1.0\r\nServiceID: speaker1\r\nServiceType: 3\r\nDataSessionID: 77\r\nCSeq: 2\r\nSimple: 1\r\n" +
                "CryptoVer: 1\r\nCryptoAbility: 222\r\nCryptoAlg: aes128-ctr\r\nFillp: 0\r\nTCP: 1\r\nCodec: 1\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=40100--1\r\n\r\n",
            text(setup.single()),
        )
        assertEquals(2, audio.only<AudioEvent.SetupSent>().cseq)
    }

    /**
     * 第七处故意差异：声明 PCM 时，SETUP 的 `Codec` 头跟随手机在 `ParaAudio` 里给的号。
     *
     * 参考实现固定写本端的 `AudioCodec.wire`（PCM 是 0）。实测鸿蒙 4.2 请求
     * `ParaAudio=SampleRate=48000;Format=16;ChannelNum=2;Codec=1` 时收到 `Codec: 0` 的 SETUP 完全不回应：
     * 不应答 SETUP、也不连数据端口，3 秒后 TEARDOWN，表现是「投屏正常但车机没有声音」。
     * 2102 能力声明里 1 就是 PCM（见 [DmsdpConfig.Speaker.audioProfile]），所以这里回写手机那套编号。
     */
    @Test
    fun setupCodecFollowsThePhoneRequestWhenDeclaringPcm() {
        val audio = AudioRecorder()
        val session = speakerSession(speaker = DmsdpConfig.Speaker(codec = AudioCodec.PCM), audio = audio)
        session.phoneDeviceInfo()
        session.speakerProfile()
        val body = "msdp_trigger_method: OPTIONS\r\n"
        session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText(
                "SET_PARAMETER * DMSDP/1.0\r\ncseq: 5\r\nserviceid: speaker1\r\nservicetype: 3\r\n" +
                    "datasessionid: 592827640\r\nsimple: 1\r\n" +
                    "paraaudio: SampleRate=48000;Format=16;ChannelNum=2;Codec=1\r\n" +
                    "content-length: ${body.length}\r\n\r\n" + body,
            ),
            1_000,
        )
        assertEquals(1, audio.only<AudioEvent.Requested>().stream.requestedCodec)
        val setup = text(session.audioListenersReady(40100, 1_050).single())
        assertTrue("Codec: 1\r\n" in setup, "SETUP 的 Codec 跟随手机请求：$setup")
        assertEquals(1, audio.only<AudioEvent.SetupSent>().wireCodec)

        // 手机没写 Codec：仍按本端声明的编码（PCM -> 0）。
        val plain = AudioRecorder()
        val second = speakerSession(speaker = DmsdpConfig.Speaker(codec = AudioCodec.PCM), audio = plain)
        second.phoneDeviceInfo()
        second.speakerProfile()
        second.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), 1_000)
        assertTrue("Codec: 0\r\n" in text(second.audioListenersReady(40100, 1_050).single()), "没写 Codec 时按声明")
    }

    @Test
    fun setupHeadersFollowTransportAndCodec() {
        fun setup(transport: AudioTransport, wireCodec: Int) =
            String(SpeakerSession.setupMessage("-3", 9, 20000, transport, wireCodec))
        val prefix = "SETUP * DMSDP/1.0\r\nServiceID: speaker1\r\nServiceType: 3\r\nDataSessionID: -3\r\nCSeq: 9\r\nSimple: 1\r\n" +
            "CryptoVer: 1\r\nCryptoAbility: 222\r\nCryptoAlg: aes128-ctr\r\n"
        val transport = "Transport: RTP/AVP/UDP;unicast;client_port=20000--1\r\n\r\n"
        assertEquals(prefix + "Fillp: 0\r\nTCP: 1\r\nCodec: 0\r\n" + transport, setup(AudioTransport.TCP, 0))
        assertEquals(prefix + "Fillp: 0\r\nCodec: 1\r\n" + transport, setup(AudioTransport.UDP, 1), "UDP 不带 TCP 头")
        assertEquals(prefix + "Fillp: 1\r\nCodec: 0\r\n" + transport, setup(AudioTransport.FILLP, 0))
    }

    @Test
    fun setupAnswerIsMatchedByCSeq() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)
        val other = session.feed(DmsdpChannel.CONTROL, phone.encryptedText("DMSDP/1.0 200 OK\r\nCSeq: 7\r\n\r\n"), 1_060)
        assertIs<DmsdpEvent.UnhandledControlMessage>(other.single(), "CSeq 对不上的应答不归扬声器管")

        val answer = session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText("DMSDP/1.0 200 OK\r\nCSeq: 2\r\nServiceID: speaker1\r\nTransport: RTP/AVP/TCP;unicast;server_port=50000\r\n\r\n"),
            1_100,
        )
        assertEquals("77", assertIs<DmsdpEvent.AudioSessionEstablished>(answer.single()).dataSessionId)
        val answered = audio.only<AudioEvent.SetupAnswered>()
        assertTrue(answered.accepted)
        assertEquals(50, answered.afterMillis)
        assertTrue("Transport: RTP/AVP/TCP;unicast;server_port=50000" in answered.toString(), "头部原样进日志")

        // 同一个 CSeq 再来一次就不认了。
        assertIs<DmsdpEvent.UnhandledControlMessage>(
            session.feed(DmsdpChannel.CONTROL, phone.encryptedText("DMSDP/1.0 200 OK\r\nCSeq: 2\r\n\r\n"), 1_200).single(),
        )
    }

    @Test
    fun rejectedSetupIsOnlyReported() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)
        val answer = session.feed(DmsdpChannel.CONTROL, phone.encryptedText("DMSDP/1.0 406 Not Acceptable\r\nCSeq: 2\r\n\r\n"), 1_100)
        assertTrue(answer.isEmpty())
        assertEquals(406, audio.only<AudioEvent.SetupAnswered>().statusCode)
        assertTrue(audio.events.any { it is AudioEvent.Note && "没有接受" in it.message })
    }

    @Test
    fun teardownIsAnsweredAndClosesTheDataSession() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)
        val teardown = session.feed(DmsdpChannel.CONTROL, trigger("TEARDOWN", cseq = "6"), 9_000)
        assertEquals(3, teardown.size, "$teardown")
        assertEquals(
            "DMSDP/1.0 200 OK\r\nServiceID: speaker1\r\nServiceType: 3\r\nDataSessionID: 77\r\nCSeq: 6\r\n\r\n",
            text(teardown[0]),
        )
        assertIs<DmsdpAction.CloseAudioTransport>(teardown[1])
        assertEquals("77", assertIs<DmsdpEvent.AudioSessionTornDown>(teardown[2]).dataSessionId)
        val ended = audio.only<AudioEvent.Ended>()
        assertEquals(AudioEndReason.PEER_TEARDOWN, ended.reason)
        assertEquals(40100, ended.summary.setupPort)
        assertEquals(null, ended.summary.setupAnswer)
        assertTrue("手机没有应答 SETUP" in ended.toString() && "数据端口否" in ended.toString(), ended.toString())

        // 拆掉以后手机的应答不再归扬声器管。
        assertIs<DmsdpEvent.UnhandledControlMessage>(
            session.feed(DmsdpChannel.CONTROL, phone.encryptedText("DMSDP/1.0 200 OK\r\nCSeq: 2\r\n\r\n"), 9_100).single(),
        )
        // 不认识的会话也回 200。
        val stray = session.feed(DmsdpChannel.CONTROL, trigger("TEARDOWN", sessionId = "78", cseq = "7"), 9_200)
        assertTrue(text(stray.single()).contains("DataSessionID: 78\r\nCSeq: 7\r\n"))
    }

    @Test
    fun newTriggerReplacesTheOldSessionAndRepeatedTriggerResendsSetup() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)
        val again = session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS", cseq = "8"), 2_000)
        assertEquals(2, again.size, "同一个会话重复触发：200 + 重发 SETUP")
        assertTrue(text(again[0]).startsWith("DMSDP/1.0 200 OK"))
        assertTrue("CSeq: 3\r\n" in text(again[1]) && text(again[1]).startsWith("SETUP"))

        val replaced = session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS", sessionId = "90", cseq = "9"), 3_000)
        assertEquals(4, replaced.size, "$replaced")
        assertTrue("DataSessionID: 90" in text(replaced[0]))
        assertIs<DmsdpAction.CloseAudioTransport>(replaced[1])
        assertIs<DmsdpEvent.AudioSessionTornDown>(replaced[2])
        assertIs<DmsdpAction.OpenAudioListeners>(replaced[3])
        assertEquals(AudioEndReason.REPLACED, audio.only<AudioEvent.Ended>().reason)
        assertEquals(listOf("77", "90"), audio.events.filterIsInstance<AudioEvent.Requested>().map { it.stream.dataSessionId })
    }

    @Test
    fun malformedOrUnknownTriggersGetNoReply() {
        val audio = AudioRecorder()
        val session = speakerSession(audio = audio)
        val noSession = phone.encryptedText(
            "SET_PARAMETER * DMSDP/1.0\r\ncseq: 5\r\nserviceid: speaker1\r\nsimple: 1\r\n\r\nmsdp_trigger_method: OPTIONS\r\n",
        )
        assertTrue(session.feed(DmsdpChannel.CONTROL, noSession, 0).isEmpty())
        assertTrue(session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS", cseq = "x"), 0).isEmpty())
        assertTrue(session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS", simple = "0"), 0).isEmpty())
        assertIs<DmsdpEvent.UnhandledControlMessage>(session.feed(DmsdpChannel.CONTROL, trigger("PAUSE"), 0).single())
        assertEquals(4, audio.events.count { it is AudioEvent.Note })
        assertTrue(audio.events.none { it is AudioEvent.Requested })
        // 会话照常可用。
        assertTrue(session.feed(DmsdpChannel.CONTROL, phone.encryptedText("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\n\r\n"), 0).isEmpty())
    }

    @Test
    fun audioParameterUpdateIsAcknowledged() {
        val session = speakerSession()
        val update = session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 11\r\nServiceID: speaker1\r\nDataSessionID: 77\r\nAudioParameter: SampleRate=44100\r\n\r\n"),
            0,
        )
        assertEquals("DMSDP/1.0 200 OK\r\nServiceID: speaker1\r\nServiceType: 3\r\nDataSessionID: 77\r\nCSeq: 11\r\n\r\n", text(update.single()))
    }

    @Test
    fun audioFocusMessagesAreReportedWithoutReply() {
        val audio = AudioRecorder()
        val session = speakerSession(audio = audio)
        assertTrue(session.feed(DmsdpChannel.CONTROL, focus(1, "1"), 0).isEmpty(), "焦点消息不回应")
        assertTrue(session.feed(DmsdpChannel.CONTROL, focus(5, "1"), 0).isEmpty())
        assertTrue(session.feed(DmsdpChannel.CONTROL, focus(14, "{\"contentType\":1,\"focusType\":3,\"usage\":12}"), 0).isEmpty())
        val messages = audio.events.filterIsInstance<AudioEvent.PhoneFocus>().map { it.message }
        assertEquals(
            listOf(PhoneAudioFocus.Action.REQUEST, PhoneAudioFocus.Action.ABANDON, PhoneAudioFocus.Action.REQUEST),
            messages.map { it.action },
        )
        assertEquals(listOf(AudioRoute.MEDIA, AudioRoute.MEDIA, AudioRoute.NAVIGATION), messages.map { it.route })
        assertTrue(messages[2].split)
        assertTrue(audio.events.any { it is AudioEvent.Note && "看出手机在分流" in it.message })
        // 没声明扬声器时照旧按未处理交出。
        val (plain, _) = encryptedSession()
        assertIs<DmsdpEvent.UnhandledDeviceData>(plain.feed(DmsdpChannel.CONTROL, focus(1, "1"), 0).single())
    }

    /** 车机的焦点变化：module 4，type 是变化（-2 暂时失去），数据是本机设备 ID；加密前、没声明扬声器时不发。 */
    @Test
    fun carFocusChangeGoesOutAsModuleFourRecord() {
        val audio = AudioRecorder()
        val session = speakerSession(audio = audio)
        val sent = session.sendAudioFocus(CarAudioFocus.LOST_TRANSIENT)
        val record = phone.decrypt(sent.sends(DmsdpChannel.CONTROL).single().let { it.copyOfRange(3, it.size) })
        assertContentEquals(hex("24" + "00000004" + "fffffffe" + "0040"), record.copyOf(11), "\$ ‖ module 4 ‖ type -2 ‖ 长度 64")
        assertEquals(Synthetic.SELF_ID, String(record, 11, record.size - 11, Charsets.US_ASCII))
        assertEquals(CarAudioFocus.LOST_TRANSIENT, audio.only<AudioEvent.CarFocusSent>().change)
        assertEquals(listOf(1, -1, -2, -3), CarAudioFocus.entries.map { it.type })

        val (plain, _) = encryptedSession()
        assertTrue(plain.sendAudioFocus(CarAudioFocus.GAINED).isEmpty(), "没声明扬声器不发")
        val early = DmsdpSession(Synthetic.config(speaker = DmsdpConfig.Speaker()), Synthetic.KEY, SequenceRandom())
        assertTrue(early.sendAudioFocus(CarAudioFocus.GAINED).isEmpty(), "还没加密不发")
        session.close()
        assertTrue(session.sendAudioFocus(CarAudioFocus.GAINED).isEmpty(), "关了不发")
    }

    /** 分流：音乐（第 8 路）和导航（第 0 路）交错在同一条连接上，各自带着通道交出，序号各算各的。 */
    @Test
    fun splitStreamsAreRoutedByBus() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)
        session.onConnected(DmsdpChannel.AUDIO, 1_300)
        session.feed(DmsdpChannel.CONTROL, focus(14, "{\"contentType\":1,\"focusType\":3,\"usage\":12}"), 1_350)
        val music = (0 until 4).map { AudioSynthetic.rtp(200 + it, AudioSynthetic.pcm(it * 1024), ssrc = 1008) }
        val nav = (0 until 2).map { AudioSynthetic.rtp(7 + it, AudioSynthetic.pcm(it * 1024), ssrc = 1000) }
        val order = listOf(music[0], music[1], music[2], nav[0], music[3], nav[1])
        val wire = order.withIndex().fold(ByteArray(0)) { acc, (i, packet) ->
            acc + AudioSynthetic.frame(FrameKind.CONTROL, AudioSynthetic.seal(packet, i + 1), sealed = true)
        }
        session.feed(DmsdpChannel.AUDIO, wire, 0, wire.size, 1_400)
        assertEquals(
            listOf(8 to AudioRoute.MEDIA, 8 to AudioRoute.MEDIA, 8 to AudioRoute.MEDIA, 0 to AudioRoute.NAVIGATION,
                8 to AudioRoute.MEDIA, 0 to AudioRoute.NAVIGATION),
            audio.routes,
        )
        val routed = audio.events.filterIsInstance<AudioEvent.StreamRouted>()
        assertEquals(listOf(1008L to AudioRoute.MEDIA, 1000L to AudioRoute.NAVIGATION), routed.map { it.ssrc to it.route })
        session.close()
        val summary = audio.only<AudioEvent.Ended>().summary
        assertEquals(0L, summary.sequenceGaps, "各路分开计序号，交错不算跳变")
        assertTrue("第 8 路 4 包" in summary.streams && "第 0 路 2 包" in summary.streams, summary.streams)
    }

    /** 还没看出分流时第 0 路当媒体（不分流的手机导航混在这一路里）；收到第 8 路之后第 0 路才单独放。 */
    @Test
    fun busZeroStaysMediaUntilSplittingIsSeen() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)
        session.onConnected(DmsdpChannel.AUDIO, 1_300)
        var wire = ByteArray(0)
        (0 until 3).forEach { wire += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(it, AudioSynthetic.pcm(it * 1024), ssrc = 1000)) }
        wire += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(50, AudioSynthetic.pcm(0), ssrc = 1008))
        wire += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(3, AudioSynthetic.pcm(3 * 1024), ssrc = 1000))
        session.feed(DmsdpChannel.AUDIO, wire, 0, wire.size, 1_400)
        assertEquals(
            listOf(0 to AudioRoute.MEDIA, 0 to AudioRoute.MEDIA, 0 to AudioRoute.MEDIA, 8 to AudioRoute.MEDIA, 0 to AudioRoute.NAVIGATION),
            audio.routes,
        )
        val routed = audio.events.filterIsInstance<AudioEvent.StreamRouted>().map { it.ssrc to it.route }
        assertEquals(listOf(1000L to AudioRoute.MEDIA, 1008L to AudioRoute.MEDIA, 1000L to AudioRoute.NAVIGATION), routed, "换通道也报一次")
    }

    /** 通话、铃声和媒体在同一条连接上按 SSRC 分路：高 16 位的用途决定通道，不认识的用途按媒体并标出来。 */
    @Test
    fun callAndRingtoneStreamsAreRoutedByTheUsageInTheSsrc() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)
        session.onConnected(DmsdpChannel.AUDIO, 1_300)
        val media = 0x0001_03F0L
        val call = 0x0002_03EAL
        val ring = 0x0006_03E8L
        val odd = 0x0063_03E9L
        val order = listOf(media to 10, media to 11, media to 12, call to 50, ring to 70, media to 13, call to 51, odd to 90)
        var wire = ByteArray(0)
        order.forEachIndexed { i, (ssrc, sequence) ->
            wire += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(sequence, AudioSynthetic.pcm(i * 1024), ssrc = ssrc))
        }
        session.feed(DmsdpChannel.AUDIO, wire, 0, wire.size, 1_400)
        assertEquals(
            listOf(AudioRoute.MEDIA, AudioRoute.MEDIA, AudioRoute.MEDIA, AudioRoute.CALL, AudioRoute.RINGTONE, AudioRoute.MEDIA,
                AudioRoute.CALL, AudioRoute.MEDIA),
            audio.routes.map { it.second },
        )
        val routed = audio.events.filterIsInstance<AudioEvent.StreamRouted>()
        assertEquals(listOf(1, 2, 6, 99), routed.map { it.usage })
        assertEquals(listOf(false, false, false, true), routed.map { it.unknownUsage })
        assertTrue("用途 99 不认识，按媒体放" in routed.last().toString(), routed.last().toString())
    }

    /** 通话状态 504、语音助手状态 505：module 1 和 4 都认，交出时带着 module；格式不对只记诊断；没声明扬声器照旧不处理。 */
    @Test
    fun callAndVoiceStatesAreReportedFromEitherModule() {
        val audio = AudioRecorder()
        val session = speakerSession(audio = audio)
        assertTrue(session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(1, 504, "{\"callState\":1}".toByteArray()), 0).isEmpty())
        assertTrue(session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(4, 504, "{\"callState\":2}".toByteArray()), 0).isEmpty())
        assertTrue(session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(1, 505, "{\"voiceState\":1}".toByteArray()), 0).isEmpty())
        session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(1, 504, "oops".toByteArray()), 0)
        val calls = audio.events.filterIsInstance<AudioEvent.PhoneCallState>()
        assertEquals(listOf(1 to 1L, 2 to 4L), calls.map { it.state to it.module })
        assertEquals(PhoneCallPhase.RINGING, calls[0].phase)
        assertTrue("module 4" in calls[1].toString(), calls[1].toString())
        val voice = audio.only<AudioEvent.PhoneVoiceState>()
        assertTrue(voice.active)
        assertTrue(audio.events.any { it is AudioEvent.Note && "格式不对" in it.message })
        assertTrue(audio.events.none { it is AudioEvent.PhoneFocus }, "type 504 在 module 4 上不当焦点消息")

        val (plain, _) = encryptedSession()
        assertIs<DmsdpEvent.UnhandledDeviceData>(
            plain.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(1, 504, "{\"callState\":1}".toByteArray()), 0).single(),
        )
    }

    /** 声明 PCM 时：收够能放的 PCM 包判「能出声」；声明 AAC 时什么都不判。 */
    @Test
    fun pcmThatPlaysIsReportedOnce() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio, DmsdpConfig.Speaker(codec = AudioCodec.PCM))
        session.onConnected(DmsdpChannel.AUDIO, 1_300)
        val (bytes, _) = AudioSynthetic.pcmStream(FrameKind.LENGTH32, sealed = true, count = 60)
        session.feed(DmsdpChannel.AUDIO, bytes, 0, bytes.size, 1_400)
        assertEquals(PcmVerdict.WORKS, audio.only<AudioEvent.CodecVerdict>().verdict)
        assertEquals(PhoneRef.keyOf(Synthetic.PEER_ID), audio.only<AudioEvent.CodecVerdict>().phone?.key)

        val aac = AudioRecorder()
        val other = toSetupSent(aac)
        other.onConnected(DmsdpChannel.AUDIO, 1_300)
        other.feed(DmsdpChannel.AUDIO, bytes, 0, bytes.size, 1_400)
        other.close()
        assertTrue(aac.events.none { it is AudioEvent.CodecVerdict }, "声明 AAC 不判")
    }

    /** 声明 PCM，手机发了焦点请求，整个连接都没开数据会话：结束时判「不出声」；太短的连接、开过数据会话的不判。 */
    @Test
    fun pcmWithoutAnyDataSessionIsReportedAtClose() {
        fun run(closeAt: Long, trigger: Boolean): List<PcmVerdict> {
            val audio = AudioRecorder()
            val session = speakerSession(DmsdpConfig.Speaker(codec = AudioCodec.PCM), audio)
            session.phoneDeviceInfo()
            session.speakerProfile()
            session.feed(DmsdpChannel.CONTROL, focus(1, "1"), 1_000)
            if (trigger) session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), 2_000)
            session.onTick(closeAt)
            session.close()
            return audio.events.filterIsInstance<AudioEvent.CodecVerdict>().map { it.verdict }
        }
        assertEquals(listOf(PcmVerdict.NO_DATA_SESSION), run(closeAt = 11_000, trigger = false))
        assertEquals(emptyList(), run(closeAt = 10_999, trigger = false), "不到 10 秒不下结论")
        assertEquals(emptyList(), run(closeAt = 60_000, trigger = true), "开过数据会话就不算")
    }

    /** 声明 PCM，手机发来的载荷不像声音：判「不出声」。 */
    @Test
    fun noiseUnderPcmIsReported() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio, DmsdpConfig.Speaker(codec = AudioCodec.PCM))
        session.onConnected(DmsdpChannel.AUDIO, 1_300)
        val random = java.util.Random(7)
        var wire = ByteArray(0)
        repeat(4) { i ->
            val noise = ByteArray(4096).also { random.nextBytes(it) }
            wire += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, noise))
        }
        session.feed(DmsdpChannel.AUDIO, wire, 0, wire.size, 1_400)
        assertEquals(PcmVerdict.UNPLAYABLE, audio.only<AudioEvent.CodecVerdict>().verdict)
    }

    @Test
    fun setupSharesTheRequestCounterWithKeepAlive() {
        val session = toSetupSent(AudioRecorder())
        session.feed(DmsdpChannel.CONTROL, phone.encryptedText("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\n\r\n"), 100_500)
        val get = session.onTick(101_000).single()
        assertEquals("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\n\r\n", text(get), "SETUP 用了 2，保活接着用 3")
    }

    @Test
    fun audioConnectionBytesBecomePackets() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)
        session.onConnected(DmsdpChannel.AUDIO, 1_300)
        assertEquals(250L, audio.only<AudioEvent.PeerConnected>().afterSetupMillis)
        val (bytes, payloads) = AudioSynthetic.pcmStream(FrameKind.LENGTH32, sealed = true, count = 5)
        var pos = 0
        while (pos < bytes.size) {
            val n = minOf(1000, bytes.size - pos)
            assertTrue(session.feed(DmsdpChannel.AUDIO, bytes, pos, n, 1_400).isEmpty())
            pos += n
        }
        assertContentEquals(bytes, audio.raw.toByteArray(), "原始字节一个不少")
        assertEquals(payloads.size, audio.packets.size)
        payloads.forEachIndexed { i, expected -> assertContentEquals(expected, audio.packets[i]) }
        assertTrue(audio.only<AudioEvent.FramingDetected>().framing.frameEncrypted)
        assertEquals(4096, audio.only<AudioEvent.FirstPacket>().payloadLength)

        session.onClosed(DmsdpChannel.AUDIO, 2_000)
        assertEquals(bytes.size.toLong(), audio.only<AudioEvent.PeerClosed>().bytes)
        session.close()
        val summary = audio.only<AudioEvent.Ended>().summary
        assertEquals(AudioEndReason.SESSION_CLOSED, audio.only<AudioEvent.Ended>().reason)
        assertTrue(summary.dataConnected)
        assertEquals(bytes.size.toLong(), summary.dataBytes)
        assertEquals(5L, summary.packets)
    }

    @Test
    fun listenerFailuresDoNotBreakTheSession() {
        val audio = AudioRecorder().apply { throwOnEvent = true }
        val session = speakerSession(audio = audio)
        val out = session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), 0)
        assertIs<DmsdpAction.Send>(out[0])
        assertTrue(out.any { it is DmsdpAction.OpenAudioListeners })
        assertTrue(out.any { it is DmsdpEvent.AudioNote })
        assertTrue(session.audioListenersReady(40100, 0).any { it is DmsdpAction.Send })
    }

    @Test
    fun listenFailureEndsTheDataSessionWithoutSetup() {
        val audio = AudioRecorder()
        val session = speakerSession(audio = audio)
        session.feed(DmsdpChannel.CONTROL, trigger("OPTIONS"), 0)
        val failed = session.audioListenersFailed("端口都被占了")
        assertTrue(failed.none { it is DmsdpAction.Send })
        assertEquals(AudioEndReason.LISTEN_FAILED, audio.only<AudioEvent.Ended>().reason)
        assertEquals(0, audio.only<AudioEvent.Ended>().summary.setupPort)
        // 之后开好的端口没人要：要求关掉。
        assertIs<DmsdpAction.CloseAudioTransport>(session.audioListenersReady(40100, 0).single())
    }

    /** 真机（v16/v17 日志）里的 `##` 私有帧：18 字节头 + 1400 字节裸 PCM。 */
    private fun privateFrame(sequence: Int, firstData: Int = 0, secondData: Int = 0): ByteArray {
        val head = "2323886d03fedcba03020000" + "%08x".format(sequence) + "0578"
        val header = head.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val payload = ByteArray(18 + 1400)
        System.arraycopy(header, 0, payload, 0, header.size)
        payload[18] = firstData.toByte()
        payload[19] = secondData.toByte()
        return payload
    }

    /**
     * 真机里的组头帧：类型 0x0301，数据区前 24 字节是元数据，后面才是 PCM。
     *
     * 数据区开头（v21 日志实测）：
     * `03 03 | 09 32 69 7a | 00 00 00 01 | 00 00 00 08 | 00 00 20 12 | 00 00 00 00 | 00 00`
     * 其中 `0x2012 = 8210 = 1400×5 + 1210`，是同组 6 包的声明长度之和。
     */
    private fun groupHeaderFrame(sequence: Int = 0): ByteArray {
        val head = "2323886d03fedcba03010000" + "%08x".format(sequence) + "0578"
        val header = head.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val payload = ByteArray(18 + 1400)
        System.arraycopy(header, 0, payload, 0, header.size)
        val meta = "03030932697a000000010000000800002012000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        System.arraycopy(meta, 0, payload, 18, meta.size)
        return payload
    }

    /**
     * 类型 0x0301 也是音频，要放；但它的 24 字节组头不是声音，必须剥掉。
     *
     * 早先误以为整个 0x0301 都是「流信息」而跳过，结果每 6 包丢 1 包（v20 日志里
     * `非音频跳过 100` 对应 `0x0301×100`），带宽只剩 156 KiB/s，播放器持续欠载。
     * v21 改成整包放，结果每 42.5ms 把 6 帧元数据当声音播一次 —— 真机听感是「有规律的咔、咔」。
     */
    @Test
    fun the0301TypeIsAudioTooButItsGroupHeaderIsStripped() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)

        session.feed(DmsdpChannel.CONTROL, phone.encryptedFrame(groupHeaderFrame()), 1_100)
        assertEquals(1, audio.packets.size, "0x0301 也要当声音放")
        assertEquals(1376, audio.packets[0].size, "剥掉 24 字节组头后放 1376 字节")
        assertTrue(
            audio.events.filterIsInstance<AudioEvent.Note>().none { "非音频" in it.message },
            "不该再报「非音频」：${audio.events.filterIsInstance<AudioEvent.Note>()}",
        )
    }

    /**
     * 鸿蒙 4.2 不连数据端口，SETUP 之后把 PCM 直接灌在控制连接上（`##` 私有帧）。
     *
     * 这些包以前被当成「不认识的二进制载荷」丢掉，表现是投屏正常但一点声音都没有。
     * 现在要能从控制连接上认出来、当裸 PCM 交给播放链路。
     */
    @Test
    fun privateFramesOnTheControlChannelArePlayedAsPcm() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)
        assertIs<AudioEvent.SetupSent>(audio.events.last(), "先走到 SETUP 已发")

        session.feed(DmsdpChannel.CONTROL, phone.encryptedFrame(privateFrame(1, 0x01, 0x7F)), 1_100)
        session.feed(DmsdpChannel.CONTROL, phone.encryptedFrame(privateFrame(2, 0x02, 0x7F)), 1_101)

        assertEquals(2, audio.packets.size, "两包都要交给播放链路")
        assertEquals(1400, audio.packets[0].size, "去掉 18 字节帧头后是裸 PCM")
        assertEquals(0x01, audio.packets[0][0].toInt(), "载荷原样交出")
        assertEquals(0x7F, audio.packets[0][1].toInt() and 0xFF)
        // 标成连续流：上层据此不按数据会话关播放器（4.2 每 3 秒换一次会话号）。
        assertTrue(audio.continuous.all { it }, "控制连接的私有帧都是连续流")

        // 不再当成「不认识的二进制载荷」。
        assertTrue(audio.events.none { it is AudioEvent.FramingUnknown }, "不该再报认不出分帧")
        // 第一包要出一条「手机改从控制连接发声音」的诊断。
        assertTrue(
            audio.events.filterIsInstance<AudioEvent.Note>().any { "控制连接" in it.message },
            "要提示手机改走控制连接：${audio.events.filterIsInstance<AudioEvent.Note>()}",
        )
    }

    /**
     * 手机拆除会话、还没请求新的之间（真机上约 25ms），控制连接上的声音没断，不能丢。
     *
     * 鸿蒙 4.2 每 3 秒拆一次会话再换号重开。拆除之后 `current` 是空的，
     * 如果这时直接返回，每 3 秒就丢 25ms 音频，听得出顿。
     */
    @Test
    fun privateFramesKeepPlayingBetweenTeardownAndTheNextRequest() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)
        session.feed(DmsdpChannel.CONTROL, phone.encryptedFrame(privateFrame(1, 0x01, 0x7F)), 1_100)
        assertEquals(1, audio.packets.size)

        // 手机拆除当前会话：current 变空。
        session.feed(DmsdpChannel.CONTROL, trigger("TEARDOWN", cseq = "6"), 1_200)

        // 新的请求还没来，控制连接上的音频照常到：必须继续放。
        session.feed(DmsdpChannel.CONTROL, phone.encryptedFrame(privateFrame(2, 0x03, 0x7F)), 1_210)
        assertEquals(2, audio.packets.size, "拆除到下次请求之间的音频不能丢")
        assertEquals(0x03, audio.packets[1][0].toInt())
    }

    /**
     * 手机只是应答了 SETUP（200）但没连数据端口时，声音照样从控制连接取。
     *
     * 这是鸿蒙 4.2 的常态：它连 200 都不回。判据只能是「数据端口有没有连上」（[SpeakerSession.Stream.data]），
     * 不能是「有没有应答」——只看应答会把 4.2 的声音丢掉。
     */
    @Test
    fun privateFramesStillPlayWhenThePhoneAnsweredButNeverConnectedTheDataPort() {
        val audio = AudioRecorder()
        val session = toSetupSent(audio)
        session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText("DMSDP/1.0 200 OK\r\nCSeq: 2\r\nServiceID: speaker1\r\n\r\n"),
            1_060,
        )
        session.feed(DmsdpChannel.CONTROL, phone.encryptedFrame(privateFrame(1, 0x01, 0x7F)), 1_100)

        assertEquals(1, audio.packets.size, "只应答、没连端口，声音还是要从控制连接取")
    }
}
