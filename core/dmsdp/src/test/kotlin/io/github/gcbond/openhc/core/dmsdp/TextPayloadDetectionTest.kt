package io.github.gcbond.openhc.core.dmsdp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DmsdpMessage.looksLikeText] 的判据。
 *
 * 这个判据被控制连接用来区分「文本消息」和「二进制载荷」：判成文本就会送去解析，
 * 解析失败抛异常会让整条会话作废、手机断开。
 *
 * 2026-11-22 的回归就是这里退化了：一度用「首字节是大写字母 + 含 `\r\n\r\n`」当判据，
 * 结果鸿蒙 NEXT 发的二进制块（以 `R`/`V`/`G` 开头、中间恰好含 `\r\n\r\n`）被当成文本，
 * 表现是「设备控制连接网络错误（读失败：SocketException: Software caused connection abort）」。
 */
class TextPayloadDetectionTest {

    @Test
    fun realTextMessagesAreText() {
        assertTrue(
            DmsdpMessage.looksLikeText("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 5\r\n\r\n".toByteArray()),
            "请求首行是「方法 + 空格 + 目标」",
        )
        assertTrue(
            DmsdpMessage.looksLikeText("DMSDP/1.0 200 OK\r\nCSeq: 1\r\n\r\n".toByteArray()),
            "应答以版本词开头",
        )
        assertTrue(
            DmsdpMessage.looksLikeText("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 9\r\n\r\n".toByteArray()),
        )
        assertTrue(
            DmsdpMessage.looksLikeText("OPTIONS * DMSDP/1.0\r\n\r\n".toByteArray()),
        )
        // 带正文：只看首行。
        assertTrue(
            DmsdpMessage.looksLikeText("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\n\r\nbody".toByteArray()),
        )
    }

    /**
     * 二进制块，首字节碰巧是大写字母、中间又碰巧含 `\r\n\r\n` —— 绝不能判成文本。
     *
     * 这几条就是回归当天的形状：宽判据会把它们送进解析器。
     */
    @Test
    fun binaryBlocksStartingWithAnUppercaseLetterAreNotText() {
        val cases = mapOf(
            "R 开头，控制字符紧跟" to (byteArrayOf(0x52, 0x00, 0x01, 0x02) + "\r\n\r\n".toByteArray() + ByteArray(20) { it.toByte() }),
            "V 开头，含 0x00" to (byteArrayOf(0x56, 0x00, 0x01, 0x02, 0x03) + "\r\n\r\n".toByteArray() + ByteArray(10) { 0xFF.toByte() }),
            "G 开头，无空格" to (byteArrayOf(0x47, 0x45, 0x54, 0x00, 0x00) + "\r\n\r\n".toByteArray()),
            "D 开头二进制（不是 DMSDP/1.0）" to (byteArrayOf(0x44, 0x00, 0x11) + "\r\n\r\n".toByteArray()),
            "S 开头但首行没有空格" to "SET_PARAMETER\r\n\r\n".toByteArray(),
            "首行含非 ASCII" to (byteArrayOf(0x53, 0x45, 0x54, 0x80.toByte()) + " x\r\n\r\n".toByteArray()),
        )
        for ((name, payload) in cases) {
            assertFalse(DmsdpMessage.looksLikeText(payload), "$name 不是文本消息")
        }
    }

    @Test
    fun rtpAndPrivateAudioFramesAreNotText() {
        // RTP 头：0x80 打头。
        assertFalse(DmsdpMessage.looksLikeText(byteArrayOf(0x80.toByte(), 0x60, 0x12, 0x34) + ByteArray(20)))
        // `##` 私有帧。
        val private = "2323886d03fedcba03020000000000010578".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertFalse(DmsdpMessage.looksLikeText(private + ByteArray(1400)))
        // 全 0。
        assertFalse(DmsdpMessage.looksLikeText(ByteArray(1400)))
        // 空载荷。
        assertFalse(DmsdpMessage.looksLikeText(ByteArray(0)))
    }
}
