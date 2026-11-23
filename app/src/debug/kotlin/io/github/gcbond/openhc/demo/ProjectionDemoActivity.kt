package io.github.gcbond.openhc.demo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.platform.media.DisplayRefreshRate
import io.github.gcbond.openhc.platform.media.FrameRatePolicy
import io.github.gcbond.openhc.platform.media.VideoDecoder
import io.github.gcbond.openhc.platform.media.VideoStats
import io.github.gcbond.openhc.projection.ProjectionView
import io.github.gcbond.openhc.settings.VideoPreferences
import java.util.Locale

/**
 * 仅调试构建：不连手机，把运行时现生成的合成码流解码到全屏投屏视图上，叠一层统计和触点信息。
 * 码流不放在 assets 里：生成器是纯 Kotlin 写的（I_PCM + P_Skip），不依赖车机的编码器，也不往仓库里放二进制。
 * 切到后台时送帧照常进行，回来时可以确认画面立即恢复（解码器在后台输出到占位 Surface）。
 * 「暂停出帧」模拟手机画面不变时不发帧：暂停后切后台再回来，画面应当马上回到暂停时的那一帧（解码器重放缓存）。
 * 「上屏节拍」当场切换解码器的节拍（初值取设置页的开关），「送帧抖动」让送帧时刻前后随机偏几毫秒。
 *
 * android4-support 分支：改成普通 Activity + 原生 [ProjectionView]（原来用 Compose 包了一层）。
 */
class ProjectionDemoActivity : Activity() {

    private lateinit var decoder: VideoDecoder
    private lateinit var feeder: SyntheticFeeder
    private lateinit var projection: ProjectionView

    private var videoWidth = 0
    private var videoHeight = 0
    private var feedFps = 0
    private var feedPaused = false
    private var framePacing = true
    private var feedJitterMs = 0
    private var touchText: String? = null
    private var resyncText: String? = null

    private lateinit var statsView: TextView
    private lateinit var refreshView: TextView
    private lateinit var touchView: TextView
    private lateinit var resyncView: TextView
    private lateinit var pauseButton: Button
    private lateinit var pacingButton: Button
    private lateinit var jitterButton: Button

    private var refreshText: String? = null
    private var declaredFps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val refreshRate = DisplayRefreshRate.ofDefaultDisplay(this)
        declaredFps = FrameRatePolicy.declaredFps(refreshRate)
        val fpsChoices = listOf(declaredFps, 30, 60).distinct()

        decoder = VideoDecoder(listener = object : VideoDecoder.Listener {
            override fun onVideoSizeChanged(width: Int, height: Int) = runOnUiThread {
                videoWidth = width
                videoHeight = height
                projection.setVideoSize(width, height)
            }

            override fun onStats(stats: VideoStats) = runOnUiThread { statsView.text = stats.toString() }

            override fun onKeyFrameNeeded(reason: VideoDecoder.ResyncReason) = runOnUiThread {
                resyncText = reason.name
                renderResync()
            }
        }, options = VideoPreferences(this).decoderOptions())
        framePacing = decoder.framePacing
        feeder = SyntheticFeeder(decoder, declaredFps)
        feedFps = feeder.fps
        feeder.start()

        refreshText = refreshRate?.let { String.format(Locale.ROOT, "%.2f", it) }

        // 全屏画面 + 左上统计 + 右下按钮，用 FrameLayout 叠起来。
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        projection = ProjectionView(this).apply {
            this.onInput = { input -> onInput(input) }
            fullScreen = true
            immersive = true
        }
        root.addView(projection, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        projection.attachDecoder(decoder)

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        statsView = smallText("")
        refreshView = smallText("")
        touchView = smallText("")
        resyncView = smallText("")
        overlay.addView(statsView)
        overlay.addView(refreshView)
        overlay.addView(touchView)
        overlay.addView(resyncView)
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(12); topMargin = dp(12) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button(getString(R.string.demo_toggle_orientation)) { feeder.toggleOrientation() })
        buttons.addView(button(getString(R.string.demo_cycle_fps)) {
            val next = fpsChoices[(fpsChoices.indexOf(feeder.fps) + 1) % fpsChoices.size]
            feeder.fps = next
            feedFps = next
            renderRefresh()
        })
        buttons.addView(button(getString(R.string.demo_key_frame)) { feeder.requestKeyFrame() })
        pauseButton = button(getString(R.string.demo_pause_feed)) {
            feedPaused = !feedPaused
            feeder.paused = feedPaused
            pauseButton.setText(if (feedPaused) R.string.demo_resume_feed else R.string.demo_pause_feed)
        }
        buttons.addView(pauseButton)
        pacingButton = button(getString(R.string.demo_pacing_on)) {
            framePacing = !framePacing
            decoder.framePacing = framePacing
            pacingButton.setText(if (framePacing) R.string.demo_pacing_on else R.string.demo_pacing_off)
        }
        buttons.addView(pacingButton)
        jitterButton = button(getString(R.string.demo_jitter, 0)) {
            val next = JITTER_CHOICES[(JITTER_CHOICES.indexOf(feedJitterMs) + 1) % JITTER_CHOICES.size]
            feeder.jitterMs = next
            feedJitterMs = next
            jitterButton.text = getString(R.string.demo_jitter, feedJitterMs)
        }
        buttons.addView(jitterButton)
        root.addView(buttons, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(12)
            bottomMargin = dp(12)
        })

        setContentView(root)
        renderRefresh()
        renderTouch()
        renderResync()
    }

    override fun onDestroy() {
        projection.attachDecoder(null)
        feeder.stop()
        decoder.close()
        super.onDestroy()
    }

    private fun onInput(input: HiSightInput) {
        feeder.onInput(input)
        if (input is HiSightInput.Touch) {
            touchText = input.action.name + " " + input.points.joinToString(" ") { "${it.id}@(${it.x},${it.y})" } +
                " / ${input.width}x${input.height}"
            renderTouch()
        }
    }

    private fun renderRefresh() {
        refreshView.text = getString(
            R.string.demo_refresh,
            refreshText ?: getString(R.string.demo_refresh_unknown),
            declaredFps,
            feedFps,
        )
    }

    private fun renderTouch() {
        touchView.text = touchText?.let { getString(R.string.demo_touch, it) } ?: getString(R.string.demo_touch_none)
    }

    private fun renderResync() {
        resyncView.text = resyncText?.let { getString(R.string.demo_resync, it) } ?: ""
        resyncView.visibility = if (resyncText == null) TextView.GONE else TextView.VISIBLE
    }

    private fun smallText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 12f
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 「送帧抖动」依次切换的档位（毫秒）。 */
        val JITTER_CHOICES = listOf(0, 4, 8)
    }
}
