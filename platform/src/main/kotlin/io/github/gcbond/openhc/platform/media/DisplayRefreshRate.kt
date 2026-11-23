package io.github.gcbond.openhc.platform.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View

/**
 * 读屏幕刷新率，作为向手机声明帧率的依据（怎么换算见 [FrameRatePolicy]）。
 *
 * 读的是屏幕当前显示模式的刷新率（`Display.getMode()`，安卓 6.0 起都有）：安卓 12 起系统可能给单个应用
 * 改写 `Display.getRefreshRate()` 的返回值（按应用限帧），显示模式的值才是屏幕实际的刷新周期。
 * 读不到时退回 `Display.getRefreshRate()`。厂商有可能报错刷新率，真机上要核对一次。
 */
object DisplayRefreshRate {

    /** [display] 的刷新率（Hz），读不到返回 null。 */
    fun of(display: Display?): Float? {
        if (display == null) return null
        // 安卓 6.0 起读显示模式（系统可能按应用限帧，模式值才是屏幕真实周期）；
        // 4.1～5.1 上 PlatformCompat 退回 Display.getRefreshRate()。
        val hz = io.github.gcbond.openhc.platform.compat.PlatformCompat.refreshRate(display)
        return if (hz.isFinite() && hz > 0f) hz else null
    }

    /** 视图所在屏幕的刷新率；视图还没挂到窗口上时退回默认屏幕。 */
    fun of(view: View): Float? = of(io.github.gcbond.openhc.platform.compat.PlatformCompat.displayOf(view)) ?: ofDefaultDisplay(view.context)

    /** 默认屏幕（车机主屏）的刷新率。可以用应用 Context。 */
    fun ofDefaultDisplay(context: Context): Float? = of(defaultDisplay(context))

    /**
     * 默认屏幕。`DisplayManager` 是 API 17 才有；安卓 4.1（16）上退回 `WindowManager.getDefaultDisplay()`。
     */
    @Suppress("DEPRECATION")
    fun defaultDisplay(context: Context): Display? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
        }

    /** 默认屏幕按 [FrameRatePolicy.declaredFps] 换算出的声明帧率。 */
    fun declaredFps(context: Context, maxFps: Int = FrameRatePolicy.DEFAULT_MAX_FPS): Int =
        FrameRatePolicy.declaredFps(ofDefaultDisplay(context), maxFps)

    /**
     * [display] 的刷新时序，交给 [VideoDecoder.setDisplayTiming]；[display] 为 null 时返回 null。
     * 帧时间偏移和送显提前量是安卓 5.0 起公开的两项，读不到（异常）记 0。
     */
    fun timingOf(display: Display?): DisplayTiming? {
        if (display == null) return null
        val hz = of(display)
        val period = if (hz != null && hz in PLAUSIBLE_MIN_HZ..PLAUSIBLE_MAX_HZ) (1e9 / hz).toLong() else 0L
        // getAppVsyncOffsetNanos / getPresentationDeadlineNanos 是 API 21 才有；4.1 上拿不到，补偿记 0
        // （节拍略保守，画面照常显示）。
        val timing = io.github.gcbond.openhc.platform.compat.PlatformCompat.presentationTiming(display)
        return DisplayTiming(period, timing?.first ?: 0L, timing?.second ?: 0L)
    }

    private const val PLAUSIBLE_MIN_HZ = 20f
    private const val PLAUSIBLE_MAX_HZ = 500f
}
