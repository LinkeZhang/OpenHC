package io.github.gcbond.openhc.ui

import android.graphics.Bitmap
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 二维码编码（ZXing，Apache-2.0）。纯逻辑，由 JVM 单元测试覆盖。 */
internal object QrCodes {
    /**
     * 编成模块矩阵：一个模块一个点，四周留 [margin] 个模块的空白（扫码要有静区）。内容按 UTF-8 编码、不做 URL 编码，
     * 纠错等级 M（车机屏幕清楚，够用，码也不至于太密）。
     */
    fun encode(content: String, margin: Int = 2): BitMatrix {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        // 宽高给 0：按最小尺寸出，一个模块一个点，放大交给界面。
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /**
     * 编成 [sizePx] 见方的位图，白底黑块。
     *
     * 老车机屏幕分辨率低，放大时不做平滑（[ImageView] 上设 filterBitmap = false），
     * 让模块边缘保持锐利，扫码更容易成功。
     */
    fun bitmap(content: String, sizePx: Int, margin: Int = 2): Bitmap {
        val matrix = encode(content, margin)
        val modules = matrix.width
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val my = y * modules / sizePx
            for (x in 0 until sizePx) {
                val mx = x * modules / sizePx
                pixels[y * sizePx + x] = if (matrix.get(mx, my)) BLACK else WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    /** 不透明黑（ARGB 0xFF000000）。 */
    private const val BLACK = -0x1000000

    /** 不透明白（ARGB 0xFFFFFFFF）。 */
    private const val WHITE = -1
}
