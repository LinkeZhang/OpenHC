package io.github.gcbond.openhc.core.dmsdp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 鸿蒙 4.2 把声音灌在控制连接上时用的 `##` 私有帧。
 *
 * 头部样本直接取自 v16/v17 真机日志（1418 字节一包，1400 字节裸 PCM）。
 */
class PrivateAudioFramesTest {

    /**
     * 真机日志里的前三包头部（只记了前 32 字节，18 字节帧头齐全）。
     * 帧头结构：`##`(2) + 标识(6) + 类型(2) + 00 00(2) + 序号(4) + 长度(2) = 18。
     *
     * 第 1 包类型是 0x0301（流信息，数据区是 `03 03 01 4a 3b 87…`，不是 PCM）；
     * 第 2、3 包类型是 0x0302（音频帧）。
     */
    private val infoHead = "2323886d03fedcba03010000000000000578"
    private val audioHeads = listOf(
        "2323886d03fedcba03020000000000010578",
        "2323886d03fedcba03020000000000020578",
    )

    /** 16 位双声道一帧 4 字节。 */
    private val frameBytes = 4

    /** 按真机头拼一包：头 18 字节 + [dataLength] 字节载荷。 */
    private fun frame(head: String, dataLength: Int = 1400, fill: Byte = 0): ByteArray {
        val header = head.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("样本头部应该是 18 字节", PrivateAudioFrames.HEADER_BYTES, header.size)
        val out = ByteArray(header.size + dataLength) { fill }
        System.arraycopy(header, 0, out, 0, header.size)
        return out
    }

    @Test
    fun recognisesTheFramesFromTheRealDevice() {
        val payload = frame(audioHeads[0])
        assertTrue("应该认出真机帧", PrivateAudioFrames.isPrivateFrame(payload, 0, payload.size))
        val info = frame(infoHead)
        assertTrue("流信息帧也是这种帧头", PrivateAudioFrames.isPrivateFrame(info, 0, info.size))
    }

    @Test
    fun parsesHeaderFields() {
        val parser = PrivateAudioFrames()
        val payload = frame(audioHeads[0])
        val parsed = parser.parse(payload, 0, payload.size, frameBytes)
        assertNotNull("真机帧应该能拆出来", parsed)
        assertEquals(1, parsed!!.sequence)
        assertEquals(1400, parsed.length)
        assertEquals(PrivateAudioFrames.HEADER_BYTES, parsed.offset)
        assertEquals(1L, parser.frames)
        assertEquals(1418L, parser.totalBytes)
    }

    /**
     * 三种类型都是音频，都要放。
     *
     * v20 日志的带宽核对（4.249 秒、0x0301/0x0302/0x0303 各 100/400/100 包）：
     *   只放 0x0302       → 128.7 KiB/s
     *   放 0x0302+0x0303  → 156.5 KiB/s
     *   三种全放          → 188.7 KiB/s ≈ 48kHz 双声道 16 位的 187.5 KiB/s（偏差 0.6%）
     * 所以 0x0301 也是声音。排除它必然丢 1/6 的音频，播放器持续欠载、卡顿。
     */
    @Test
    fun playsAllThreeTypesBecauseAllOfThemCarryAudio() {
        val parser = PrivateAudioFrames()
        // 0x0301 是组头，剥掉 24 字节元数据后放 1376 字节；另外两种是纯 PCM，各 1400。
        val expected = mapOf(infoHead to 1400 - PrivateAudioFrames.GROUP_PREFIX_BYTES, audioHeads[0] to 1400, "2323886d03fedcba03030000000000030578" to 1400)
        for ((head, size) in expected) {
            val raw = frame(head)
            val payload = if (head == infoHead) withGroupHeader(raw) else raw
            val parsed = parser.parse(payload, 0, payload.size, frameBytes)
            assertNotNull("每种类型都要放：$head", parsed)
            assertEquals("类型 $head 放出来的长度", size, parsed!!.length)
        }
        assertEquals(3L, parser.frames)
        assertEquals(0L, parser.skipped)
        val histogram = parser.typeHistogram()
        for (type in listOf("0x0301", "0x0302", "0x0303")) {
            assertTrue("直方图里要有 $type：$histogram", type in histogram)
        }
    }

    /** 每种类型都留头部样本，用来确定它前面有没有元数据头。 */
    @Test
    fun keepsHeadSamplesPerType() {
        val parser = PrivateAudioFrames()
        parser.parse(frame(infoHead), 0, 1418, frameBytes)
        val samples = parser.headSamples()
        assertTrue("要有 0x0301 的头部样本：$samples", "0x0301=" in samples)
        assertTrue("头部样本不能是空的：$samples", samples.length > 20)
    }

    @Test
    fun sequencesAreTrackedAndGapsCounted() {
        val parser = PrivateAudioFrames()
        parser.parse(frame(audioHeads[0]), 0, 1418, frameBytes)
        parser.parse(frame(audioHeads[1]), 0, 1418, frameBytes)
        assertEquals(0, parser.gaps)

        // 序号跳到 9：记一次跳变。
        parser.parse(frame("2323886d03fedcba03020000000000090578"), 0, 1418, frameBytes)
        assertEquals(1, parser.gaps)
        assertEquals(3L, parser.frames)
    }

    @Test
    fun rejectsOtherBinaryPayloads() {
        val parser = PrivateAudioFrames()

        // 不是 ## 开头。
        val text = "DMSDP/1.0 200 OK\r\nCSeq: 2\r\n\r\n".toByteArray(Charsets.US_ASCII)
        assertFalse(PrivateAudioFrames.isPrivateFrame(text, 0, text.size))
        assertNull(parser.parse(text, 0, text.size, frameBytes))

        // ## 开头但长度字段离谱（0）。
        val zeroLength = frame(audioHeads[0]).also { it[16] = 0; it[17] = 0 }
        assertFalse(PrivateAudioFrames.isPrivateFrame(zeroLength, 0, zeroLength.size))

        // 太短，装不下帧头。
        val short = byteArrayOf(0x23, 0x23, 0x00)
        assertFalse(PrivateAudioFrames.isPrivateFrame(short, 0, short.size))
    }

    @Test
    fun truncatesTrailingPaddingToDeclaredLength() {
        val parser = PrivateAudioFrames()
        // 实际 1418 字节，但头里只声明 1024：只放 1024 字节，多出来的算填充。
        val payload = frame(audioHeads[0]).also { it[16] = 0x04; it[17] = 0x00 }
        val parsed = parser.parse(payload, 0, payload.size, frameBytes)
        assertEquals(1024, parsed!!.length)
    }

    @Test
    fun usesWhatActuallyArrivedWhenTheHeaderDeclaresMore() {
        val parser = PrivateAudioFrames()
        // 头里声明 1400，但只给了 100 字节数据：按 100 字节裁到整帧（100 是 4 的倍数）再放。
        // 整包丢会直接造成卡顿（v18 就是这么丢掉三分之一音频的）。
        val payload = frame(audioHeads[0], dataLength = 100)
        val parsed = parser.parse(payload, 0, payload.size, frameBytes)
        assertNotNull("按实际收到的长度放，不整包丢", parsed)
        assertEquals(100, parsed!!.length)
        assertEquals("记一笔「声明的比收到的长」", 1L, parser.short)
        assertEquals(1L, parser.frames)
    }

    /** 真机 0x0301 数据区开头那 24 字节组头。`0x2012 = 8210 = 1400×5 + 1210`。 */
    private val groupHeader = "03030932697a000000010000000800002012000000000000"

    /** 把 24 字节组头写进包的数据区开头。 */
    private fun withGroupHeader(payload: ByteArray): ByteArray {
        val header = groupHeader.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        System.arraycopy(header, 0, payload, PrivateAudioFrames.HEADER_BYTES, header.size)
        return payload
    }

    /**
     * 长度不是整数帧的 PCM：尾巴要**留到下一包拼上**，不能丢。
     *
     * 真机 1228 字节包的数据区是 1210 字节，1210 % 4 = 2。一组 6 包（1:4:1）的纯音频是
     * 1376 + 1400×4 + 1210 = 8186 字节，8186 % 4 = 2 —— 说明字节流是跨包连续的，
     * 那 2 字节是下一个采样的前半，不是填充。v21 每包各自裁掉它，等于每 42.5ms 丢半个采样。
     */
    @Test
    fun carriesTheOddTailOverToTheNextPacketInsteadOfDroppingIt() {
        val parser = PrivateAudioFrames()
        // 数据区 1400，但头里声明 1210：只放 1210 字节，1210 % 4 = 2，剩 2 字节留给下一包。
        val first = frame(audioHeads[0]).also { it[16] = 0x04.toByte(); it[17] = 0xBA.toByte() }
        // 那 2 字节尾巴在数据区的最后（18 + 1210 - 2 = 1226），标成 0xAB 0xCD。
        first[1226] = 0xAB.toByte()
        first[1227] = 0xCD.toByte()
        val parsed = parser.parse(first, 0, first.size, frameBytes)
        assertNotNull("1210 字节的包还是要放", parsed)
        assertEquals("先放 1208 字节整帧", 1208, parsed!!.length)
        assertEquals("尾巴留起来，不丢", 1L, parser.odd)

        // 下一包 1400 字节：前面 2 字节是上一包的尾巴。
        val second = frame(audioHeads[1])
        second[18] = 0x11
        second[19] = 0x22
        val next = parser.parse(second, 0, second.size, frameBytes)
        assertNotNull("下一包要接上尾巴", next)
        // 2 + 1400 = 1402，1402 % 4 = 2，所以放 1400 字节，剩 2 字节继续留给再下一包。
        assertEquals(1400, next!!.length)
        assertEquals("尾巴在前", 0xAB, next.data[next.offset].toInt() and 0xFF)
        assertEquals("尾巴在后", 0xCD, next.data[next.offset + 1].toInt() and 0xFF)
        assertEquals("接上一包开头", 0x11, next.data[next.offset + 2].toInt() and 0xFF)
        assertEquals(0x22, next.data[next.offset + 3].toInt() and 0xFF)
        assertEquals("这一包也留了 2 字节尾巴", 2L, parser.odd)
    }

    /** 字节流跨包连续，拼起来一个字节都不能少。 */
    @Test
    fun doesNotLoseAnyByteAcrossAPacketGroup() {
        val parser = PrivateAudioFrames()
        // 真机一组：0x0301(组头 24) + 4×0x0302 + 0x0303(1210)。
        val sizes = listOf(1400, 1400, 1400, 1400, 1400, 1210)
        var played = 0
        for ((index, size) in sizes.withIndex()) {
            val head = if (index == 0) infoHead else audioHeads[index % 2]
            val raw = frame(head, dataLength = size)
            val payload = if (index == 0) withGroupHeader(raw) else raw
            val parsed = parser.parse(payload, 0, payload.size, frameBytes) ?: continue
            assertEquals("放出来的必须是整帧", 0, parsed.length % frameBytes)
            played += parsed.length
        }
        // 纯音频 1376 + 1400×4 + 1210 = 8186；能放 8186 - 8186 % 4 = 8184，剩 2 字节等下一组。
        assertEquals("一字节都不该多丢（只留了跨组的 2 字节尾巴）", 8184, played)
    }

    /**
     * 0x0301 的 24 字节组头不是声音，要剥掉。
     *
     * 真机数据区开头固定是：
     * `03 03 | 09 32 69 7a | 00 00 00 01 | 00 00 00 08 | 00 00 20 12 | 00 00 00 00 | 00 00`
     * 其中 `0x2012 = 8210 = 1400 × 5 + 1210`，正好是同组 6 包的声明长度之和 —— 铁证它是组头。
     * 不剥的话每 42.5ms 把 6 帧元数据当声音放一次，听起来就是「有规律的咔、咔」。
     */
    @Test
    fun stripsTheGroupHeaderOfType0301() {
        val parser = PrivateAudioFrames()
        val payload = withGroupHeader(frame(infoHead))

        val parsed = parser.parse(payload, 0, payload.size, frameBytes)
        assertNotNull(parsed)
        assertEquals("剥掉 24 字节组头后放 1376 字节", 1376, parsed!!.length)
        assertEquals("数据从组头之后开始", PrivateAudioFrames.HEADER_BYTES + 24, parsed.offset)
        assertEquals(1L, parser.stripped)
        assertEquals("1376 是 4 的倍数，不该算裁过尾巴", 0L, parser.odd)
    }

    /** 0x0302 / 0x0303 的数据区是纯 PCM，哪怕碰巧以 03 03 开头也不能剥。 */
    @Test
    fun doesNotStripAnythingFromPlainPcmTypes() {
        val parser = PrivateAudioFrames()
        for (head in listOf(audioHeads[0], "2323886d03fedcba03030000000000030578")) {
            val payload = frame(head)
            // 声音波形碰巧以 03 03 开头。
            payload[18] = 0x03
            payload[19] = 0x03
            val parsed = parser.parse(payload, 0, payload.size, frameBytes)
            assertEquals("纯 PCM 一字节都不能剥：$head", 1400, parsed!!.length)
            assertEquals(PrivateAudioFrames.HEADER_BYTES, parsed.offset)
        }
        assertEquals(0L, parser.stripped)
    }

    /** 数据区比一帧还短：这一包放不出东西，但字节要留着等下一包，不能丢。 */
    @Test
    fun keepsFramesTooShortToHoldOnePcmFrameForTheNextPacket() {
        val parser = PrivateAudioFrames()
        // 声明长度 3，不足一帧（4 字节）：返回 null，但 3 字节进 carry。
        val payload = frame(audioHeads[0], dataLength = 3).also { it[16] = 0x00; it[17] = 0x03 }
        payload[18] = 0xAA.toByte()
        assertNull("这一包凑不出一帧", parser.parse(payload, 0, payload.size, frameBytes))
        assertEquals(1L, parser.odd)
        assertEquals(0L, parser.frames)

        // 下一包补上 1 字节就凑成一帧：前面应该是那 3 个字节。
        val next = frame(audioHeads[1], dataLength = 5).also { it[16] = 0x00; it[17] = 0x05 }
        val parsed = parser.parse(next, 0, next.size, frameBytes)
        assertNotNull("3 + 5 = 8 字节，能放两帧", parsed)
        assertEquals(8, parsed!!.length)
        assertEquals("上一包留的字节在前", 0xAA, parsed.data[parsed.offset].toInt() and 0xFF)
    }
}
