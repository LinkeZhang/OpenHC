package io.github.gcbond.openhc.platform.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.os.Build

/**
 * 一路声音的用途：决定播放器音轨的 AudioAttributes（车机按它选音量档和输出路径），也是申请音频焦点的单位
 * （见 [AudioFocusPolicy]）。枚举本身不碰安卓类，纯逻辑里也能用。
 */
enum class AudioUsage(val label: String) {
    MEDIA("媒体"),
    NAVIGATION("导航"),

    /** 提示音、语音助手、通知等短促的声音。 */
    ASSISTANT("提示与语音"),

    /** 通话：音轨属性按语音通话设，车机的回声消除要拿这一路当参考信号。 */
    CALL("通话"),
    RINGTONE("铃声"),
}

/**
 * 部分车机系统给「导航」扩展的内容类型：用途填媒体、内容类型填它，声音走车机独立的导航声道、跟车机的导航音量。
 * 标准安卓不认这个值：安卓 11 起 `setContentType` 抛 IllegalArgumentException；8～10 不抛、悄悄改掉
 * （8、9 把用途清成未知，10 把内容类型清成未知），只有把建好的属性读回来才看得出。
 */
const val CAR_NAVIGATION_CONTENT_TYPE = 6

// AudioAttributes 的用途/内容类型常量是编译期常量（内联），所以在这里直接写数值，安卓 4.1 上引用也不报错。
// 值来自 android.media.AudioAttributes：USAGE_MEDIA=1、USAGE_ASSISTANCE_NAVIGATION_GUIDANCE=12、
// USAGE_ASSISTANT=16、USAGE_ASSISTANCE_SONIFICATION=13、USAGE_VOICE_COMMUNICATION=2、USAGE_NOTIFICATION_RINGTONE=6；
// CONTENT_TYPE_MUSIC=2、CONTENT_TYPE_SPEECH=1、CONTENT_TYPE_SONIFICATION=4。
internal const val USAGE_MEDIA = 1
internal const val USAGE_ASSISTANCE_NAVIGATION_GUIDANCE = 12
internal const val USAGE_ASSISTANT = 16
internal const val USAGE_ASSISTANCE_SONIFICATION = 13
internal const val USAGE_VOICE_COMMUNICATION = 2
internal const val USAGE_NOTIFICATION_RINGTONE = 6
internal const val CONTENT_TYPE_MUSIC = 2
internal const val CONTENT_TYPE_SPEECH = 1
internal const val CONTENT_TYPE_SONIFICATION = 4

/**
 * 播放器音轨用的属性，以及给日志看的说明（普通情况为空）。
 *
 * 这里只放两个 int（用途、内容类型），不持有 `AudioAttributes` 对象：那个类是 API 21 才有，
 * 安卓 4.1 上连字段类型都不能出现，否则加载本类就 NoClassDefFoundError。真正建 AudioAttributes
 * 在 [audioAttributes]（只在 API 21+ 调用）。
 */
class TrackAttributes(val usage: Int, val contentType: Int, val note: String)

/**
 * 播放器音轨用的属性。导航那一路先试车机扩展的导航属性（用途媒体 + 内容类型 [CAR_NAVIGATION_CONTENT_TYPE]），
 * 系统抛异常（安卓 11 起）就退回标准导航用途（标准导航用途在很多车机上仍跟媒体音量走）。8～10 的系统不抛、
 * 悄悄改掉，这时照样用建出来的属性，只在说明里写明读回的值，别再写成「车机导航声道」。申请音频焦点仍用
 * [attributes]，播报时压低音乐靠的是焦点，不受这里影响。
 */
// 故意传标准以外的内容类型：车机系统扩展的值，标准系统会抛异常并退回。
@SuppressLint("WrongConstant")
fun AudioUsage.trackAttributes(): TrackAttributes {
    if (this != AudioUsage.NAVIGATION) return plain()
    // 车机扩展的导航内容类型：安卓 11 起 setContentType 会抛 IllegalArgumentException；8～10 不抛、悄悄改掉，
    // 只有把建好的属性读回来才看得出。低版本（<21）根本没有 AudioAttributes，直接用标准换算。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
        return TrackAttributes(USAGE_MEDIA, CAR_NAVIGATION_CONTENT_TYPE, "车机导航声道（安卓 4.x 按 streamType 建，用途换算成媒体）")
    }
    return try {
        val car = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(CAR_NAVIGATION_CONTENT_TYPE)
            .build()
        val kept = car.usage == AudioAttributes.USAGE_MEDIA && car.contentType == CAR_NAVIGATION_CONTENT_TYPE
        val note = if (kept) {
            "车机导航声道（用途媒体 + 内容类型 $CAR_NAVIGATION_CONTENT_TYPE）"
        } else {
            "系统没认下车机扩展的导航内容类型（读回用途 ${car.usage}、内容类型 ${car.contentType}），多半跟媒体音量走"
        }
        TrackAttributes(car.usage, car.contentType, note)
    } catch (e: IllegalArgumentException) {
        TrackAttributes(plain().usage, plain().contentType, "标准导航用途（系统不认车机扩展的导航内容类型）")
    }
}

/** 不带车机扩展的标准属性值（纯 int，任何版本都能算）。 */
private fun AudioUsage.plain(): TrackAttributes = when (this) {
    AudioUsage.MEDIA -> TrackAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC, "")
    AudioUsage.NAVIGATION -> TrackAttributes(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, CONTENT_TYPE_SPEECH, "")
    AudioUsage.ASSISTANT -> TrackAttributes(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) USAGE_ASSISTANT else USAGE_ASSISTANCE_SONIFICATION,
        CONTENT_TYPE_SPEECH,
        "",
    )
    AudioUsage.CALL -> TrackAttributes(USAGE_VOICE_COMMUNICATION, CONTENT_TYPE_SPEECH, "")
    AudioUsage.RINGTONE -> TrackAttributes(USAGE_NOTIFICATION_RINGTONE, CONTENT_TYPE_SONIFICATION, "")
}

/**
 * 安卓 5.0 以前建音轨只能给 streamType，这里是 [AudioUsage] 到老 streamType 的换算。
 *
 * 车机按 streamType 选音量档：媒体/导航/提示走 STREAM_MUSIC（4.x 上导航没有独立音量档，这是系统限制），
 * 通话走 STREAM_VOICE_CALL（回声消除要它），铃声走 STREAM_RING。
 */
fun AudioUsage.legacyStreamType(): Int = when (this) {
    AudioUsage.MEDIA, AudioUsage.NAVIGATION, AudioUsage.ASSISTANT -> android.media.AudioManager.STREAM_MUSIC
    AudioUsage.CALL -> android.media.AudioManager.STREAM_VOICE_CALL
    AudioUsage.RINGTONE -> android.media.AudioManager.STREAM_RING
}

/**
 * 音轨用的 AudioAttributes。**只在 API 21+ 调用**（`AudioAttributes` 是 API 21 才有）。
 * 提示与语音在安卓 8.0 起用 `USAGE_ASSISTANT`；更低的版本没有它，用 `USAGE_ASSISTANCE_SONIFICATION`。
 */
@android.annotation.TargetApi(Build.VERSION_CODES.LOLLIPOP)
fun AudioUsage.attributes(): AudioAttributes {
    val builder = AudioAttributes.Builder()
    when (this) {
        AudioUsage.MEDIA -> builder.setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        AudioUsage.NAVIGATION -> builder.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioUsage.ASSISTANT -> builder.setUsage(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes.USAGE_ASSISTANT
            } else {
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            },
        ).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioUsage.CALL -> builder.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioUsage.RINGTONE -> builder.setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    }
    return builder.build()
}
