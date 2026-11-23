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
     * [route] 是这一包属于哪一路：鸿蒙 4.2 在导航播报时会把「音乐」和「导航」两条独立的
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

    /** 见过的组头数、组头时间戳倒退的次数（判断有没有两路交织）。 */
    private var groupStamps = 0L
    private var groupStampBack = 0L
    private var groupStampSame = 0L
    private var groupDupDropped = 0L
    private var lastGroupStamp = -1L
    /** 当前处于「重复组」丢弃态：上一个组头时间戳与本组相同。 */
    private var droppingGroup = false
    private val groupStampRecent = ArrayList<Long>(32)
    private var groupPrefixHex = ""
    /** 最近若干组头的「时间戳步长/组内总字节数」，如 `3836/8210`。 */
    private val groupTrace = ArrayList<String>(24)
    /** 最近 8 个组头的完整 24 字节（hex），看两路有没有区分字段。 */
    private val groupHeadHex = ArrayList<String>(8)
    private var lastGroupBytes = 0
    private var lastStep = 0L

    /** 各收到多少个组头（用来确认分路真的生效）。 */
    private var mediaGroups = 0L
    private var navigationGroups = 0L

    /** 组头（0x0301）总数、其中没剥成组头的个数（数据区不以 `03 03` 开头，可能是静音帧）。 */
    private var groupHeaders = 0L
    private var groupHeadersNoPrefix = 0L
    private var headerHex = ""

    /** 最近 32 个组头：(时间戳, 收到时的墙钟 ms)，给 [clockRate] 算滑动倍速。 */
    private val stampClock = ArrayList<Pair<Long, Long>>(32)

    /**
     * 音频时钟倍速：`(时间戳增量 / 90000) / (墙钟增量 / 1000)`，用**最近的**组头算，不是全程平均。
     *
     * 正常应是 1.0。导航播报时若仍是 ~1.0 而包速率翻倍，说明手机在**重复发**同一段
     * （时间戳按真实速度走，但同一批音频发两遍）；若是 ~2.0，说明音频时钟本身跑快了。
     *
     * 用滑动窗口而不是首尾：全程平均会把「音乐 140 包/秒 + 导航 280 包/秒」抹平成 1.0，
     * 正好把要看的现象消掉。
     */
    fun clockRate(): String {
        if (stampClock.size < 2) return "音频时钟 样本不足"
        val a = stampClock.first()
        val b = stampClock.last()
        val stampMs = (b.first - a.first) / 90.0
        val wallMs = (b.second - a.second).toDouble()
        if (wallMs <= 0) return "音频时钟 样本不足"
        return "音频时钟 %.2f 倍（最近 %d 组：时间戳走 %.0fms / 实际 %.0fms）".format(
            stampMs / wallMs, stampClock.size, stampMs, wallMs,
        )
    }

    /** 组头时间戳统计，形如 `组头 4084 个、时间戳倒退 0 次`。 */
    fun stampHistogram(): String =
        "组头 $groupStamps 个（未剥 $groupHeadersNoPrefix/$groupHeaders）、倒退 $groupStampBack 次、" +
            "相等 $groupStampSame 次（重复丢 $groupDupDropped 组）"

    /** 最近 24 组头的「步长/组字节」轨迹，直接看导航时步长和组大小有没有变。 */
    fun groupTraceText(): String = "组轨迹[${groupTrace.joinToString(" ")}]"

    /** 分路统计：音乐 / 导航各多少组。 */
    fun routeHistogram(): String = "分路：音乐 $mediaGroups 组、导航 $navigationGroups 组"

    /**
     * 按路的时间戳连续性：**判断「声音突然没了又跳过去」根因的关键**。
     *
     * TCP 不丢包，我们自己的队列稳态也没丢（突发超限丢 0）。如果时间戳在这里往前跳了，
     * 说明手机在视频突发时把音频从**源头**丢了（编码端实时、发不出去就丢），
     * 那我们再怎么调缓冲都没用，只能从源头/优先级治。
     */
    fun continuityText(): String = "时间戳连续性：${mediaLane.jumpText("音乐")}；${navLane.jumpText("导航")}"

    /** 最近 8 个组头的完整 24 字节，看两路交替时有没有区分字段。 */
    fun groupHeadsText(): String = "组头样本[${groupHeadHex.joinToString(" ")}]"
    fun stampSamples(): String =
        "组头时间戳[${groupStampRecent.joinToString(",")}]；首组头数据区[$groupPrefixHex]；首组头帧头[$headerHex]"

    /** 各类型号各收到多少包，给诊断用（最多记这么多种，防内存无限涨）。 */
    private val types = LinkedHashMap<Int, Long>()

    /** 各 module（帧头偏移 1～4 的音频会话号）各收到多少包，给诊断用。 */
    private val modules = LinkedHashMap<Long, Long>()

    /** module 分布，形如 `0x2854EED7×13000`。导航播报时看它是否多出一个 module。 */
    fun moduleHistogram(): String =
        modules.entries.joinToString(" ") { "0x%08X×%d".format(it.key, it.value) }

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
        // 帧头偏移 1～4 的 module 是手机那边的音频会话号。导航播报时整条流的包速率会翻倍，
        // 记录每个 module 各来多少包，用来判断导航是不是走另一个 module（能分开就能分路放）。
        // module 直方图只留最近的几种：控制连接上手机每 3 秒重建一次数据会话，会话号一直在涨，
        // 旧实现记满 8 种就不再计数，导航时想看「有没有多出一个 module」看到的却是冻结的旧值。
        // 现在满了就把计数最少的那种踢掉，保证看到的永远是当前在用的 module。
        val module = readU32Be(payload, offset + MODULE_AT)
        if (module !in modules && modules.size >= MAX_MODULES) {
            modules.minByOrNull { it.value }?.let { modules.remove(it.key) }
        }
        modules[module] = (modules[module] ?: 0L) + 1
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

        // ---- 组头：时间戳统计 + 整组去重 ----
        //
        // 实测（v272 日志）：导航播报时包速率从 140 翻到 280，但音频时钟仍是 1.00 倍，
        // 而且「最近 32 组」的时间戳跨度从 1323ms 缩到 652ms。两种可能：
        //   A. 手机把同一段音频**发两遍** → 相邻组时间戳**完全相同**；
        //   B. 每组时长减半（步长 1834）→ 时间戳不相等，只是走得密。
        // 两者都会让 32 组跨 652ms，光看跨度分不出来，必须统计「相等」次数。
        //
        // 只有 A 才能安全去重：90kHz 下两组真实音频撞同一时间戳不可能（1 tick = 11µs），
        // 所以「时间戳与上一组完全相同」就是重复组铁证，整组丢掉不误伤声音。
        // B 情况下相等次数会是 0，去重永远不触发，不会破坏正常播放。
        if (type == TYPE_INFO && prefix > 0) {
            val stamp = readU32Be(payload, offset + HEADER_BYTES + 2)
            if (lastGroupStamp >= 0 && stamp < lastGroupStamp) groupStampBack++
            if (lastGroupStamp >= 0 && stamp == lastGroupStamp) {
                groupStampSame++
                droppingGroup = true
            } else {
                droppingGroup = false
            }
            // 组头里 +16 的 BE16 是「组内总字节数」（纯音乐时 8210，v274 误写成 +14 读到 0）。
            val groupBytes = readU16Be(payload, offset + HEADER_BYTES + 16)
            val step = if (lastGroupStamp >= 0) stamp - lastGroupStamp else 0
            lastGroupBytes = groupBytes
            lastStep = step
            if (groupTrace.size >= 24) groupTrace.removeAt(0)
            groupTrace.add("$step/$groupBytes")
            // 最近 8 组的**完整 24 字节组头**：导航播报时两路交替到达，
            // 要确认两路除了时间戳以外还有没有区分字段（+6 的 1、+10 的 8 会不会不同）。
            // 分路只能靠这些字段，光靠时间戳奇偶猜是不可靠的。
            if (groupHeadHex.size >= 8) groupHeadHex.removeAt(0)
            val hn = minOf(GROUP_PREFIX_BYTES, usableDeclared)
            groupHeadHex.add(
                (0 until hn).joinToString("") { "%02x".format(payload[offset + HEADER_BYTES + it].toInt() and 0xFF) },
            )
            lastGroupStamp = stamp
            groupStamps++
            groupStampRecent.add(stamp)
            if (groupStampRecent.size > 32) groupStampRecent.removeAt(0)
            val now = System.nanoTime() / 1_000_000
            stampClock.add(stamp to now)
            if (stampClock.size > 32) stampClock.removeAt(0)
            if (groupPrefixHex.isEmpty()) {
                val n = minOf(GROUP_PREFIX_BYTES, usableDeclared)
                groupPrefixHex = (0 until n).joinToString(" ") {
                    "%02x".format(payload[offset + HEADER_BYTES + it].toInt() and 0xFF)
                }
            }
        }
        if (droppingGroup) {
            groupDupDropped++
            return null
        }

        if (prefix > 0) prefixStripped++
        if (type == TYPE_INFO) {
            groupHeaders++
            if (prefix == 0) groupHeadersNoPrefix++
            // 留第一个组头的**完整 18 字节帧头**：确认计数/分片号/长度各在哪个偏移。
            // v270 只打了数据区，看不到帧头，序号跳变率 5/6 的成因没法判断。
            if (headerHex.isEmpty()) {
                val n = minOf(HEADER_BYTES, length)
                headerHex = (0 until n).joinToString(" ") {
                    "%02x".format(payload[offset + it].toInt() and 0xFF)
                }
            }
        }
        // 这一包属于哪一路。组头（0x0301）的 +10 字段给出答案，组内后续包沿用该组的路由。
        if (type == TYPE_GROUP && prefix > 0) {
            val marker = readU32Be(payload, offset + HEADER_BYTES + 10)
            currentRoute = if (marker == ROUTE_NAVIGATION_MARKER) AudioRoute.NAVIGATION else AudioRoute.MEDIA
            if (currentRoute == AudioRoute.NAVIGATION) navigationGroups++ else mediaGroups++
        }
        val route = currentRoute
        val lane = if (route == AudioRoute.NAVIGATION) navLane else mediaLane
        // 组头时间戳按路记：这是判断「手机端有没有丢音频」的唯一依据。
        // TCP 不丢包，我们这边也没丢（突发超限丢 0），那时间戳却往前跳了，
        // 就只能是手机编码端在视频突发时把音频从源头丢了 —— 这跟缓冲策略无关，只能从源端治。
        if (type == TYPE_GROUP && prefix > 0) {
            lane.onStamp(readU32Be(payload, offset + HEADER_BYTES + 2), GROUP_STAMP_STEP)
        }
        val body = usableDeclared - prefix
        if (body <= 0) {
            oddFrames++
            return null
        }
        val dataAt = offset + HEADER_BYTES + prefix
        // **每一路各自维护跨包尾巴**：两路交织时，如果共用一份 carry，
        // 音乐那组的 2 字节尾巴会被拼到导航那组前面，两路的声音都会碎。
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

        // ---- 这一路自己的组头时间戳连续性 ----
        //
        // 两路交织时**必须分开算**：音乐和导航的时间戳各自独立递增，
        // 混在一起算步长永远是乱的（一会儿 +3836、一会儿 -3830），什么都看不出来。
        var lastStamp = -1L
        /** 时间戳前跳的次数（源端丢音频）。 */
        var jumps = 0L
        /** 前跳掉的音频总时长（tick，90kHz）。 */
        var jumpTicks = 0L
        /** 单次最大前跳（tick）。 */
        var maxJumpTicks = 0L
        /** 时间戳倒退次数（不该发生，发生了就是两路判错或乱序）。 */
        var backs = 0L

        fun onStamp(stamp: Long, expectedStep: Long) {
            if (lastStamp >= 0) {
                val step = stamp - lastStamp
                if (step < 0) {
                    backs++
                } else if (step > expectedStep + expectedStep / 2) {
                    // 超过正常步长 1.5 倍就算前跳：正常抖动远小于这个量。
                    val lost = step - expectedStep
                    jumps++
                    jumpTicks += lost
                    if (lost > maxJumpTicks) maxJumpTicks = lost
                }
            }
            lastStamp = stamp
        }

        fun jumpText(name: String): String {
            if (jumps == 0L && backs == 0L) return "$name 无前跳"
            return "$name 前跳 ${jumps} 次/共 ${jumpTicks * 1000 / 90000}ms" +
                "（最大 ${maxJumpTicks * 1000 / 90000}ms）倒退 ${backs} 次"
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
        /**
         * 计数在偏移 10（BE32，逐包递增），**不是 12**。
         *
         * 2026-11-23 用真机帧头核对：`23 2854EED7 FEDCBA03 03 02000000 0001 0578`，
         * 偏移 10 起 4 字节才是计数，偏移 14 是分片号。以前写 12，读的是「计数后半 + 分片号」，
         * 结果每包都算跳变（日志里 `序号跳变` 恒等于包数，诊断因此完全失真）。
         */
        private const val SEQUENCE_AT = 10
        private const val LENGTH_AT = 16

        /** 音频帧的类型号：数据区就是裸 PCM。 */
        const val TYPE_AUDIO = 0x0302

        /** 组头类型号：数据区前 [GROUP_PREFIX_BYTES] 字节是元数据，后面才是 PCM。 */
        const val TYPE_GROUP = 0x0301

        /**
         * 组头 +10 的 BE32 字段：`8` 是音乐那一路，`0x1000`（4096）是导航播报那一路。
         *
         * 2026-11-23 真机日志（v275）核对：纯音乐时全部是 8；导航播报时 8 和 4096 严格交替。
         * 这是**唯一可靠的区分依据**——时间戳奇偶只是表象，丢一个包就会错位；
         * 这个字段每包自带，不用猜。
         */
        const val ROUTE_NAVIGATION_MARKER = 0x1000L

        /** 组头数据区前面的元数据字节数（v21 真机日志证实）。 */
        const val GROUP_PREFIX_BYTES = 24

        /**
         * 组头时间戳的正常步长：一组 2046 帧 @48kHz = 42.6ms，90kHz 时钟下 ≈ 3834。
         * 实测 3828～3836。用来判断时间戳有没有前跳（源端丢音频）。
         */
        const val GROUP_STAMP_STEP = 3836L

        /** 旧名字，保留给上层诊断用。 */
        const val TYPE_INFO = 0x0301

        /** 一包数据的上限：正常 1400，留余量给手机换采样率或声道数。 */
        private const val MAX_DATA = 8192

        /** 类型直方图最多记这么多种。 */
        private const val MAX_TYPES = 8

        /** module 直方图最多记这么多种。 */
        private const val MAX_MODULES = 8

        /** module 字段在帧头里的偏移（帧首字节之后）。 */
        private const val MODULE_AT = 1

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
