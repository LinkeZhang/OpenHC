package io.github.gcbond.openhc.platform.compat

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.os.Build
import android.view.Display

/**
 * 平台层（media / net / bluetooth）的安卓 4.1（API 16）兼容层。
 *
 * 主线 minSdk 是 23，这里不少地方直接用了 API 17～23 的接口。android4-support 分支把它们按系统版本分流：
 * 高版本照原样走新接口，低版本走 4.1 上就有的老接口。每个函数都写明「低版本退化成什么、为什么可以这么退」。
 *
 * 注意：低版本分支里**不能出现**新 API 的引用（Kotlin 内联的常量除外），否则类加载时就会 NoSuchMethodError。
 * 所以每个分支要么用反射，要么只在 `SDK_INT >= 对应版本` 的分支里写新接口——JIT 校验是按方法来的，
 * 只要不进那个分支就不会解析到缺失的符号。
 */
@SuppressLint("NewApi")
object PlatformCompat {

    // ---------------- AudioAttributes / AudioTrack ----------------

    /**
     * 按用途建一条 16 位 PCM 流式音轨。
     *
     * `AudioAttributes` 和 `AudioTrack.Builder` 都是 API 21/23 才有的，4.1 上只能用老构造
     * `AudioTrack(streamType, rate, mask, encoding, bufferSize, MODE_STREAM)`。老的 streamType 和新的 usage
     * 大体对应（见 [legacyStreamType]），车机按 streamType 选音量档，效果和按 usage 建接近。
     *
     * @param usage 新接口的用途值（[AudioAttributes.USAGE_MEDIA] 等），只在 API 21+ 用。
     * @param streamType 老接口的 streamType（[AudioManager.STREAM_MUSIC] 等），只在 API 16～20 用。
     */
    fun newTrack(
        usage: Int,
        contentType: Int,
        streamType: Int,
        sampleRate: Int,
        channelMask: Int,
        bufferBytes: Int,
        lowLatency: Boolean,
    ): AudioTrack {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // 4.x：老构造。没有低延迟输出（PERFORMANCE_MODE 是 API 26），也没有 AudioAttributes。
            @Suppress("DEPRECATION")
            return AudioTrack(
                streamType,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
                AudioTrack.MODE_STREAM,
            )
        }
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    /**
     * 音轨当前缓冲了多少帧（还没播出去的）。
     *
     * `getBufferSizeInFrames` 是 API 23 才有。低版本返回 0，调用方按「不知道」处理：日志里少一项，播放不受影响。
     */
    fun bufferSizeInFrames(track: AudioTrack): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) track.bufferSizeInFrames else 0

    /**
     * 音轨缓冲容量（帧）。
     *
     * `getBufferCapacityInFrames` 是 API 23 才有。低版本用 `bufferSizeInBytes / frameBytes` 顶替：
     * 老接口建出来的音轨这两个值本来就相等。
     */
    fun bufferCapacityInFrames(track: AudioTrack, frameBytes: Int, fallbackBytes: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.bufferCapacityInFrames
        } else {
            // 4.x 上不查音轨的实际缓冲（老接口建出来就等于申请的字节数），直接用申请值换算。
            if (frameBytes <= 0) 0 else fallbackBytes / frameBytes
        }

    /**
     * 设置音轨音量。`AudioTrack.setVolume` 是 API 21 才有；低版本用 `setStereoVolume`（API 3，双声道左/右都设），
     * 单声道音轨也认这个调用（系统会把两路合成一路）。
     */
    @Suppress("DEPRECATION")
    fun setVolume(track: AudioTrack, volume: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            track.setVolume(volume)
        } else {
            track.setStereoVolume(volume, volume)
        }
    }

    /**
     * 阻塞写入 PCM。
     *
     * `write(ByteBuffer, int, int, int)` 是 API 23 才有。低版本用 `write(ByteArray, int, int)`：
     * 它在 MODE_STREAM 下同样是阻塞的（内部就是 blocking write），返回写入的字节数。
     */
    fun writeBlocking(track: AudioTrack, data: ByteArray, offset: Int, length: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.write(java.nio.ByteBuffer.wrap(data, offset, length), length, AudioTrack.WRITE_BLOCKING)
        } else {
            track.write(data, offset, length)
        }

    /**
     * 音轨的播放时间戳（帧位置 + 纳秒时间）。`AudioTrack.getTimestamp` 和 `AudioTimestamp` 都是 API 19 才有。
     *
     * 低版本返回 null，调用方把「硬件延迟」记为未知，只报队列和已写未播两段（日志少一项，播放不受影响）。
     */
    fun timestamp(track: AudioTrack): Pair<Long, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return null
        val stamp = android.media.AudioTimestamp()
        return if (track.getTimestamp(stamp)) stamp.framePosition to stamp.nanoTime else null
    }

    /**
     * 音轨的欠载次数。`getUnderrunCount` 是 API 24 才有；低版本返回 null（日志少一项）。
     */
    fun underrunCount(track: AudioTrack): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) track.underrunCount else null

    /** 音轨实际拿到的性能模式。API 26 才有；低版本返回 null。 */
    fun performanceMode(track: AudioTrack): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) track.performanceMode else null

    // ---------------- MediaCodec ----------------

    /**
     * 取解码器的输入缓冲。`getInputBuffer` 是 API 21 才有；低版本用 `getInputBuffers()`（API 16）按下标取，
     * 返回的是同一块内存的 ByteBuffer。
     */
    @Suppress("DEPRECATION")
    fun inputBuffer(codec: MediaCodec, index: Int): java.nio.ByteBuffer? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            codec.getInputBuffer(index)
        } else {
            codec.inputBuffers.getOrNull(index)
        }

    /**
     * 取解码器的输出缓冲。`getOutputBuffer` 是 API 21 才有；低版本用 `getOutputBuffers()`（API 16）。
     * 低版本拿到的 ByteBuffer 位置/限制不一定是这一帧的范围，调用方要按 BufferInfo 自己 seek。
     */
    @Suppress("DEPRECATION")
    fun outputBuffer(codec: MediaCodec, index: Int): java.nio.ByteBuffer? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            codec.getOutputBuffer(index)
        } else {
            codec.outputBuffers.getOrNull(index)
        }

    /**
     * 解码器的名字（日志用）。`MediaCodec.getName` 是 API 18 才有；低版本从 `MediaCodecInfo` 里取。
     * 都拿不到时返回 "codec"。
     */
    fun codecName(codec: MediaCodec): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            codec.name
        } else {
            try {
                // 4.x 上没有公开的「从 MediaCodec 反查名字」，只能按类型名猜一个能对上清单的。
                val list = MediaCodecListCompat.infos()
                list.firstOrNull { codec.javaClass.name.contains(it.name, ignoreCase = true) }?.name ?: "codec"
            } catch (e: Exception) {
                "codec"
            }
        }

    /**
     * 解码器错误的补充说明（日志用）。`CodecException.getDiagnosticInfo` 是 API 21 才有；
     * 低版本只报错误码和是否可恢复。
     */
    fun codecErrorDetail(e: MediaCodec.CodecException): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            "${e.errorCode}/${e.diagnosticInfo}"
        } else {
            "错误码 ${e.errorCode}"
        }

    // ---------------- Display ----------------

    /**
     * 视图所在的 [Display]。`View.getDisplay` 是 API 17 才有；低版本走 `WindowManager.getDefaultDisplay()`。
     */
    @Suppress("DEPRECATION")
    fun displayOf(view: android.view.View): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            view.display
        } else {
            (view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
        }


    /**
     * 屏幕的刷新率（Hz）。`Display.getMode().refreshRate` 是 API 23 才有；低版本用 `getRefreshRate()`（API 1）。
     * 拿不到返回 0。
     */
    @Suppress("DEPRECATION")
    fun refreshRate(display: Display?): Float {
        val d = display ?: return 0f
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            d.mode?.refreshRate ?: 0f
        } else {
            d.refreshRate
        }
    }

    /**
     * 视图所在屏幕的呈现时序补偿（纳秒）。`getAppVsyncOffsetNanos` / `getPresentationDeadlineNanos`
     * 都是 API 21 才有，4.1 上没法算这个补偿：返回 null，调用方按「没有补偿」处理（画面节拍略保守，不影响播放）。
     */
    fun presentationTiming(display: Display?): Pair<Long, Long>? {
        val d = display ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            d.appVsyncOffsetNanos to d.presentationDeadlineNanos
        } catch (e: Exception) {
            null
        }
    }
}

/** 解码器清单：`MediaCodecList.getCodecInfos` 是 API 21 才有，低版本用 `getCodecCount` / `getCodecInfoAt`（API 16）。 */
@SuppressLint("NewApi")
internal object MediaCodecListCompat {
    @Suppress("DEPRECATION")
    fun infos(): List<android.media.MediaCodecInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        } else {
            val count = android.media.MediaCodecList.getCodecCount()
            (0 until count).mapNotNull { android.media.MediaCodecList.getCodecInfoAt(it) }
        }
}
