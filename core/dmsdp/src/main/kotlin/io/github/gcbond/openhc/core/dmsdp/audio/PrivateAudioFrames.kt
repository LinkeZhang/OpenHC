package io.github.gcbond.openhc.core.dmsdp.audio

import io.github.gcbond.openhc.core.common.toHex

/**
 * 鸿蒙 4.2 把扬声器声音直接灌在**控制连接**上时用的私有帧。
 *
 * 实测（v16/v17 日志，1418 字节一包，约 189KB/s，正好是 48kHz 双声道 16 位 PCM 的码率）：
 *
 * ```
 * 偏移  0: 23                      '#' 帧首字节
 * 偏移  1: 88 6d 03 fe             module（BE32，等于音频数据会话号，每次连接都变）
 * 偏移  5: fe dc ba 03             魔数（BE32，0xFEDCBA03，固定不变）
 * 偏移  9: 03                     段号（1/2/3，等价于老布局里的类型号）
 * 偏移 10: 00 00 00 01             计数（BE32，逐包递增）
 * 偏移 14: 00 05                  分片号（BE16）
 * 偏移 16: 05 78                  数据长度（BE16，0x0578 = 1400）
 * 偏移 18: 裸 PCM（明文，静音时全 0）
 * ```
 *
 * 上面这个布局是 2026-11-23 用 300 个真机帧头核对出来的。注意 `module` 是 4 字节、
 * 占偏移 1～4，所以魔数 `0xFEDCBA03` 从偏移 **5** 开始。旧代码误以为偏移 0/1 是 `##`
 * 双字节魔数，其实偏移 1 是 module 的首字节——会话号首字节恰好是 `0x23` 时看着像 `##`，
 * 纯属巧合。详见 [isPrivateFrame]。
 *
 * **段号不是常量**，实测三种交替出现（稳定 1:4:1）：
 * - `0x01`：**组头**。数据区前 24 字节是元数据，后面才是 PCM。
 * - `0x02`：音频帧（数据区就是裸 PCM，静音时全 0）。
 * - `0x03`：音频帧（数据区 1210 字节，尾巴 2 字节是填充，裁掉）。
 *
 * 三种都装着声音。带宽核对（v20 日志，4.249 秒、各 100/400/100 包）：
 * 三种全放 188.7 KiB/s ≈ 48kHz 双声道 16 位的 187.5 KiB/s，只放 0x02 只有 128.7 KiB/s。
 *
 * `0x0301` 的 24 字节组头（v21 日志，两个样本）：
 *
 * ```
 * 03 03 | 09 32 69 7a | 00 00 00 01 | 00 00 00 08 | 00 00 20 12 | 00 00 00 00 | 00 00
 * 魔数    时间戳(递增)        1             8          8210 = 组内总字节数
 * ```
 *
 * `8210 = 1400 × 5 + 1210`，正好是同一组 6 包（1:4:1）的声明长度之和，所以它是组头。
 * 剥掉 24 字节后 `1400 − 24 = 1376 = 344 帧`，一组 `344 + 4×350 + 302 = 2046 帧 / 42.48ms ≈ 48.2kHz`。
 *
 * v21 没剥这 24 字节，等于每 42.5ms 把 6 帧元数据当声音播一次（23.5Hz 的周期爆音），
 * 真机听感就是「有规律的咔、咔」。
 *
 * 1418 − 18 = 1400 字节 = 350 帧 × 4 字节（16 位立体声）。
 *
 * 和走数据端口的正常路径不同，这里没有 RTP、没有加密、没有 12 字节 PCM 头，就是裸载荷。
 * 所以单独解析，解出来的 PCM 直接按 [AudioPacket] 交给播放链路。
 *
 * 不是线程安全的；只在协议线程上用。
 */
internal class PrivateAudioFrames {

    /**
     * 解析出的一包：载荷是 [data] 的 `[offset, offset + length)`。
     *
     * [route] 是这一包属于哪一路：导航播报时手机会把「音乐」和「导航」两条独立的
     * PCM 流交织在同一条控制连接上发（见 [ROUTE_NAVIGATION_MARKER]），必须分开放。
     */
    class Frame(
        val sequence: Int,
        val data: ByteArray,
        val offset: Int,
        val length: Int,
        val route: AudioRoute,
    )

    private var seen = 0L
    private var malformed = 0L
    private var lastSequence = -1
    private var sequenceGaps = 0
    private var bytes = 0L
    private var skippedTypes = 0L
    private var oddFrames = 0L
    private var truncated = 0L
    private var lastType = -1
    private var prefixStripped = 0L

    /** 各类型号各收到多少包，给诊断用（最多记这么多种，防内存无限涨）。 */
    private val types = LinkedHashMap<Int, Long>()

    /** 每种类型前几包的数据区头部，用来确定它前面有没有元数据头。 */
    private val heads = HashMap<Int, MutableList<ByteArray>>()

    /** 类型分布，形如 `0x0302×500 0x0301×2`。 */
    fun typeHistogram(): String =
        types.entries.joinToString(" ") { "0x%04x×%d".format(it.key, it.value) }

    /** 每种类型前几包的头部十六进制，形如 `0x0301=0303014a3b87… | 0x0302=00000000…`。 */
    fun headSamples(): String = heads.entries.joinToString(" | ") { (type, list) ->
        "0x%04x=%s".format(type, list.joinToString(",") { it.toHex() })
    }

    /** 认出来、当成声音交出去的帧数。 */
    val frames: Long get() = seen

    /** 收到的字节数（含帧头）。 */
    val totalBytes: Long get() = bytes

    /** 序号跳变的次数。 */
    val gaps: Int get() = sequenceGaps

    /** 帧头对不上、丢掉的数据包数。 */
    val bad: Long get() = malformed

    /** 类型不是音频（例如流信息）而跳过的帧数。 */
    val skipped: Long get() = skippedTypes

    /** 音频帧长度不是整帧（尾巴被裁掉几个字节）的帧数。 */
    val odd: Long get() = oddFrames

    /** 头里声明的长度比实际收到的长（按实际长度放）的帧数。 */
    val short: Long get() = truncated

    /** 剥掉组头元数据（[GROUP_PREFIX_BYTES] 字节）的帧数。 */
    val stripped: Long get() = prefixStripped

    // ---- 组头时间戳：判断手机那边的音频时钟是不是比实时快 ----
    //
    // 实测安卓 9 车机（v255）：到达 164 包/秒，正常 140 包/秒，快了 17%，
    // 队列 5 秒就顶到上限，然后每秒丢 165ms 音频。
    // 两种可能：① 手机音频时钟真的跑快；② 同一段音频发了多遍。
    // 组头时间戳直接给出答案：时间戳按真实速度走而包速率翻倍 → 重复发；
    // 时间戳也按 1.17 倍速度走 → 手机时钟偏快。
    private var groupStamps = 0L
    private var lastGroupStamp = -1L
    private val stampClock = ArrayList<Pair<Long, Long>>(32)

    /** 音乐 / 导航各多少个组头（组头 +10 路由标记判定）。 */
    private var mediaGroups = 0L
    private var navigationGroups = 0L

    /** 路由分布：`分路：音乐 N 组、导航 M 组`。两路交织时两个数都不为 0。 */
    fun routeHistogram(): String = "分路：音乐 $mediaGroups 组、导航 $navigationGroups 组"

    /** 音频时钟倍速：`(时间戳增量 / 90000) / (墙钟增量 / 1000)`。正常 1.00。 */
    fun clockRate(): String {
        if (stampClock.size < 2) return "音频时钟 样本不足（组头 ${groupStamps}）"
        val a = stampClock.first()
        val b = stampClock.last()
        val stampMs = (b.first - a.first) / 90.0
        val wallMs = (b.second - a.second).toDouble()
        if (wallMs <= 0) return "音频时钟 样本不足"
        return "音频时钟 %.2f 倍（最近 %d 组：时间戳走 %.0fms / 实际 %.0fms，组头共 %d）".format(
            stampMs / wallMs, stampClock.size, stampMs, wallMs, groupStamps,
        )
    }

    /** 上一次见到的类型号，给上层出诊断用。 */
    val type: Int get() = lastType

    /** 上一包头里声明的数据长度（含要被裁掉的尾巴），给上层判断有没有裁过用。 */
    var lastDeclared = 0
        private set

    /**
     * 拆一包；不是这种帧时返回 null（调用方照旧把它当未知载荷处理）。
     *
     * [frameBytes] 是一帧 PCM 的字节数（16 位双声道是 4）。
     *
     * 两点都不能做「整包丢」，否则必然丢声音、必然卡：
     * - 声明长度比实际收到的长时按实际长度放（v18 整包丢，丢掉三分之一音频）。
     * - 长度不是整数帧时**把尾巴留到下一包拼上**，不裁掉（v21 每包裁 2 字节，丢掉每 42.5ms 半个采样）。
     *
     * 返回的 [Frame.data] 可能是复用缓冲（[buffer]），调用方要立刻用掉，不能长期持有。
     * 现有播放链路（[io.github.gcbond.openhc.platform.media.AudioPlayer.queuePcm]）入队时就复制，安全。
     */
    fun parse(payload: ByteArray, offset: Int, length: Int, frameBytes: Int): Frame? {
        if (!isPrivateFrame(payload, offset, length)) {
            malformed++
            return null
        }
        bytes += length
        val type = readU16Be(payload, offset + TYPE_AT)
        lastType = type
        if (types.size < MAX_TYPES || type in types) types[type] = (types[type] ?: 0L) + 1
        // 每种类型的前几包留一份数据区头部，用来确定它前面有没有元数据头、有几个字节。
        if ((heads[type]?.size ?: 0) < HEAD_SAMPLES) {
            val shown = payload.copyOfRange(offset + HEADER_BYTES, offset + minOf(length, HEADER_BYTES + HEAD_BYTES))
            heads.getOrPut(type) { ArrayList() }.add(shown)
        }
        // 所有类型都当音频放。
        //
        // 实测鸿蒙 4.2 的音频帧类型是三种交替出现的（v20 日志，稳定 1:4:1）：
        // `0x0301` `0x0302` `0x0303`。带宽核对（4.249 秒、各 100/400/100 包）：
        //   只放 0x0302       → 128.7 KiB/s
        //   放 0x0302+0x0303  → 156.5 KiB/s
        //   三种全放          → 188.7 KiB/s ≈ 48kHz 双声道 16 位的 187.5 KiB/s  ✓（偏差 0.6%）
        // 所以 0x0301 同样装着声音；排除它就必然丢声音、必然卡。
        val declared = declaredLength(payload, offset)
        lastDeclared = declared
        // 声明长度比实际长：手机可能把一包拆成两个控制帧发（TCP 分片），也可能声明的是上限。
        // 按实际可用的长度放，不整包丢——丢整包会直接造成卡顿，裁到整帧即可。
        val available = length - HEADER_BYTES
        val usableDeclared = if (declared > available) {
            truncated++
            available
        } else {
            declared
        }
        // 0x0301 是**组头**：数据区前面还有 24 字节元数据，后面才是 PCM。
        // 不剥掉的话，每 42.5ms 就会把 6 帧元数据当声音放一次（23.5 Hz 的周期爆音，
        // 听起来就是「有规律的咔、咔」，v21 真机日志证实）。
        val prefix = groupPrefixBytes(type, payload, offset + HEADER_BYTES, usableDeclared, frameBytes)
        if (prefix > 0) prefixStripped++
        // 组头时间戳采样（必须在剥组头成功之后取：prefix == 0 时 dataAt 是裸 PCM）。
        if (prefix > 0) {
            val stamp = readU32Be(payload, offset + HEADER_BYTES + 2)
            if (stamp != lastGroupStamp) {
                lastGroupStamp = stamp
                groupStamps++
                stampClock.add(stamp to System.nanoTime() / 1_000_000)
                if (stampClock.size > 32) stampClock.removeAt(0)
            }
            // 组头 +10 是路由标记（8=音乐 / 4096=导航），唯一可靠的区分字段。
            // 安卓 9 车机实测包速率 270/秒，正好是单路（140/秒）的两倍，而时钟 1.00 倍——
            // 这就是两路交织：手机把音乐和导航当成两条独立流塞进同一条连接。
            // 不分路的话两路都灌进同一个播放器，按 1 秒/秒消费，必然堆满丢包。
            val marker = readU32Be(payload, offset + HEADER_BYTES + 10)
            currentRoute = if (marker == ROUTE_NAVIGATION_MARKER) AudioRoute.NAVIGATION else AudioRoute.MEDIA
            if (currentRoute == AudioRoute.NAVIGATION) navigationGroups++ else mediaGroups++
        }
        val route = currentRoute
        val lane = if (route == AudioRoute.NAVIGATION) navLane else mediaLane
        val body = usableDeclared - prefix
        if (body <= 0) {
            oddFrames++
            return null
        }
        val dataAt = offset + HEADER_BYTES + prefix
        // 字节流是**跨包连续**的，不能按包各自裁到整帧。
        //
        // 一组 6 包（1:4:1）的纯音频是 1376 + 1400×4 + 1210 = 8186 字节，8186 % 4 = 2：
        // 1210 那包的尾巴 2 字节不是填充，而是下一个采样的前半（低 16 位）。
        // v21 每包各自裁掉它，等于每 42.5ms 丢半个采样 —— 又一个周期性的「咔」。
        // 现在把不足一帧的尾巴留到下一包拼上，一字节都不丢。
        val total = lane.carrySize + body
        val whole = total - total % frameBytes
        if (whole <= 0) {
            // 连一帧都凑不齐：整段留着等下一包（不会丢）。
            appendCarry(lane, payload, dataAt, body)
            oddFrames++
            return null
        }
        val sequence = readS32Be(payload, offset + SEQUENCE_AT)
        if (lastSequence >= 0 && sequence != lastSequence + 1) sequenceGaps++
        lastSequence = sequence
        // 新的尾巴在**本包**末尾（carry 只补在开头，且不足一帧）。
        val tail = total - whole
        val keptBody = body - tail
        val data: ByteArray
        val at: Int
        if (lane.carrySize == 0) {
            // 没有残留：直接引用原数组，零拷贝。
            data = payload
            at = dataAt
            lane.carrySize = 0
            appendCarry(lane, payload, dataAt + keptBody, tail)
        } else {
            // 有残留：拼到复用缓冲里，避免每包新建数组（每秒 137 次分配会让车机 GC 抖）。
            data = lane.ensureBuffer(whole)
            System.arraycopy(lane.carry, 0, data, 0, lane.carrySize)
            System.arraycopy(payload, dataAt, data, lane.carrySize, keptBody)
            at = 0
            // 尾巴换成这一包末尾的字节。
            System.arraycopy(payload, dataAt + keptBody, lane.carry, 0, tail)
            lane.carrySize = tail
        }
        if (tail > 0) oddFrames++
        seen++
        return Frame(sequence, data, at, whole, route)
    }

    /**
     * 一路音频的跨包拼接状态。
     *
     * 为什么每路各一份：导航播报时手机把「音乐」「导航」两条独立 PCM 流交织在同一条连接上，
     * 一组 8186 字节不是 4 的整数倍，最后一个采样的后半（2 字节）要留到**同一路的下一包**拼上。
     * 共用一份 carry 会把音乐那组的半采样拼到导航那组前面，两路都碎。
     */
    private class Lane {
        val carry = ByteArray(MAX_FRAME_BYTES)
        var carrySize = 0
        private var buffer = ByteArray(0)

        fun ensureBuffer(size: Int): ByteArray {
            if (buffer.size < size) buffer = ByteArray(size)
            return buffer
        }
    }

    private val mediaLane = Lane()
    private val navLane = Lane()

    /** 当前组属于哪一路（组头 +10 判定，组内后续包沿用）。 */
    private var currentRoute = AudioRoute.MEDIA

    private fun appendCarry(lane: Lane, data: ByteArray, at: Int, length: Int) {
        if (length <= 0) {
            lane.carrySize = 0
            return
        }
        System.arraycopy(data, at, lane.carry, 0, length)
        lane.carrySize = length
    }

    /**
     * 这一包数据区前面有多少字节是元数据（不是 PCM）。
     *
     * 只有 [TYPE_GROUP]（`0x0301`）有：真机日志里它的数据区开头固定是
     *
     * ```
     * 03 03 | 09 32 69 7a | 00 00 00 01 | 00 00 00 08 | 00 00 20 12 | 00 00 00 00 | 00 00
     * 魔数    时间戳(递增)     1              8           8210 = 组内字节数
     * ```
     *
     * `8210 = 1400 × 5 + 1210`，正好是同一组里 6 包（1:4:1）的声明长度之和，
     * 所以这包是组头，元数据长 24 字节。剥掉后 `1400 - 24 = 1376`，是 4 的整数倍（344 帧）。
     *
     * 判得保守：魔数不对、或者剥完不是整帧，就不剥（宁可按原样放，也不要把声音剥掉）。
     */
    private fun groupPrefixBytes(type: Int, payload: ByteArray, dataAt: Int, dataLength: Int, frameBytes: Int): Int {
        // 只有组头类型才有这 24 字节；0x0302/0x0303 的数据区是纯 PCM，
        // 声音波形碰巧以 03 03 开头的情况一定会有，不能靠魔数判断。
        if (type != TYPE_GROUP) return 0
        if (dataLength <= GROUP_PREFIX_BYTES) return 0
        if (payload[dataAt].toInt() != 0x03 || payload[dataAt + 1].toInt() != 0x03) return 0
        val rest = dataLength - GROUP_PREFIX_BYTES
        if (frameBytes > 0 && rest % frameBytes != 0) return 0
        return GROUP_PREFIX_BYTES
    }

    companion object {
        /** 帧首字节，固定是 `'#'`（0x23）。 */
        const val MARKER = 0x23

        /**
         * 帧头里真正的魔数，固定在偏移 5，4 字节大端 `0xFEDCBA03`。
         *
         * 这才是唯一稳定的锚点：2026-11-23 的 300 个真机帧头样本里 100% 命中。
         * 见 [isPrivateFrame] 的说明——以前拿偏移 0/1 的 `##` 当魔数是错的。
         */
        const val MAGIC = 0xFEDCBA03L

        /**
         * 帧头长度：`'#'(1) + module(4) + 魔数(4) + 段号(1) + 计数(4) + 分片号(2) + 长度(2) = 18`。
         *
         * 详细布局见类注释。
         */
        const val HEADER_BYTES = 18
        private const val MAGIC_AT = 5
        private const val TYPE_AT = 8
        private const val SEQUENCE_AT = 12
        private const val LENGTH_AT = 16

        /** 音频帧的类型号：数据区就是裸 PCM。 */
        const val TYPE_AUDIO = 0x0302

        /** 组头类型号：数据区前 [GROUP_PREFIX_BYTES] 字节是元数据，后面才是 PCM。 */
        const val TYPE_GROUP = 0x0301

        /** 组头数据区前面的元数据字节数（v21 真机日志证实）。 */
        const val GROUP_PREFIX_BYTES = 24

        /** 旧名字，保留给上层诊断用。 */
        const val TYPE_INFO = 0x0301

        /**
         * 组头 +10 的路由标记：导航那条流是 0x1000（4096），音乐是 0x8（8）。
         * 这是唯一可靠的区分字段（时间戳奇偶会因丢包错位）。
         */
        const val ROUTE_NAVIGATION_MARKER = 0x1000L

        /** 一包数据的上限：正常 1400，留余量给手机换采样率或声道数。 */
        private const val MAX_DATA = 8192

        /** 类型直方图最多记这么多种。 */
        private const val MAX_TYPES = 8

        /** 每种类型留几包头部样本。 */
        private const val HEAD_SAMPLES = 2

        /** 头部样本留多少个字节。 */
        private const val HEAD_BYTES = 24

        /** 最多留多少字节的跨包尾巴（单声道 2 字节、双声道 4 字节都够）。 */
        private const val MAX_FRAME_BYTES = 8

        /**
         * 是不是这种私有帧。判得严一点：控制连接上正常的 DMSDP 文本消息不会长这样，
         * 长度字段也要落在合理范围，免得把别的二进制块误当成声音。
         *
         * **判据是偏移 5 的 `0xFEDCBA03`，不是偏移 0/1 的 `##`。**
         *
         * 以前这里查的是「前两个字节都是 0x23」，那是把偏移 1 的 **module 字段首字节**
         * 误当成了第二个 `#`。module 就是手机那边的音频数据会话号，每建一次连接都变：
         *
         * - 2026-11-22 的会话号是 `0x23886D03`，首字节正好 `0x23` → 看起来像 `23 23`，蒙对了，有声。
         * - 2026-11-23 的会话号是 `0x2854EED7`，首字节 `0x28` → `##` 判据失效，
         *   控制连接上**所有**音频包都被当成「认不出的二进制」丢掉，投屏正常但一点声音没有。
         *
         * 300 个真机帧头样本里 `FEDCBA03` 100% 命中，才是真正稳定的锚点。
         */
        fun isPrivateFrame(payload: ByteArray, offset: Int, length: Int): Boolean {
            if (length < HEADER_BYTES) return false
            if (payload[offset].toInt() and 0xFF != MARKER) return false
            if (readU32Be(payload, offset + MAGIC_AT) != MAGIC) return false
            return declaredLength(payload, offset) in 1..MAX_DATA
        }

        private fun declaredLength(payload: ByteArray, offset: Int): Int =
            ((payload[offset + LENGTH_AT].toInt() and 0xFF) shl 8) or (payload[offset + LENGTH_AT + 1].toInt() and 0xFF)

        private fun readU32Be(data: ByteArray, offset: Int): Long =
            ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)

        private fun readU16Be(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

        private fun readS32Be(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }
}
