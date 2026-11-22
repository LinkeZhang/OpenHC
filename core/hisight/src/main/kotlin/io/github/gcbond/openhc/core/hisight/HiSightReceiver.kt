package io.github.gcbond.openhc.core.hisight

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.hisight.control.Capabilities
import io.github.gcbond.openhc.core.hisight.control.ControlFrameDecoder
import io.github.gcbond.openhc.core.hisight.control.ControlFraming
import io.github.gcbond.openhc.core.hisight.control.MediaSetup
import io.github.gcbond.openhc.core.hisight.control.ParameterText
import io.github.gcbond.openhc.core.hisight.control.RtspMessage
import io.github.gcbond.openhc.core.hisight.control.RtspParser
import io.github.gcbond.openhc.core.hisight.control.RtspTemplates
import io.github.gcbond.openhc.core.hisight.control.parseTransport
import io.github.gcbond.openhc.core.hisight.crypto.AesCtr
import io.github.gcbond.openhc.core.hisight.input.UibcEncoder
import io.github.gcbond.openhc.core.hisight.video.VideoDepacketizer
import java.security.SecureRandom
import java.util.TimeZone

/**
 * 一次 HiSight 投屏会话的纯协议逻辑：不持有连接、线程或定时器。调用方负责建连和收发，
 * 把收到的字节喂进来，并按返回的 [HiSightOutput] 依次执行。
 *
 * 流程（三条连接都由车机主动连到手机）：
 * 1. 设备会话层告知 HiSight 端口后，连上控制连接，调用 [start]，把返回的明文 ANNOUNCE 发出去。
 * 2. 控制连接上收到的字节交给 [feedControl]。手机的 ANNOUNCE 选定加密后，后续每帧都是 AES-CTR；
 *    OPTIONS、能力查询（GET_PARAMETER）、能力选择和 SETUP 触发（SET_PARAMETER）都在这里应答。
 * 3. SETUP 应答到达时返回 [HiSightOutput.StreamNegotiated] 和 [HiSightOutput.Connect]：连视频端口，
 *    手机给了回控端口的话也要连。各自连上后调用 [onVideoConnected]、[onRemoteControlConnected]，
 *    需要的连接都就绪后会话才发 PLAY。
 * 4. 视频连接收到的字节交给 [feedVideo]，每解出一帧就同步回调 [AccessUnitSink]。
 * 5. 触摸、按键用 [sendInput] 编码，返回的字节写到回控连接。
 * 6. 结束：本机主动结束调用 [shutdown]；手机要求结束、某条连接被关闭时，对应方法会返回收尾动作。
 *
 * 手机发来的数据不合法时抛 [MalformedMessageException]，会话进入失败状态，之后应调用 [shutdown]
 * 做收尾（会尽量发出 TEARDOWN）。同一次 [feedControl] 里、出错之前已经生成的应答不会丢，
 * 会排在 [shutdown] 返回列表的最前面，保证线上顺序与逐帧发送时一致。
 *
 * 线程：控制连接相关的方法（[start]、[feedControl]、各 `on…` 通知、[shutdown]）、视频方法
 * （[feedVideo]）、输入方法（[sendInput]、[releaseInputs]）可以分别在不同线程调用，内部各有锁；
 * 同一组方法要串行调用。视频回调在 [feedVideo] 的线程上执行，回调里不要阻塞等待其他会话方法。
 *
 * @param sessionKey 认证得到的 16 字节会话密钥，三条连接共用。
 * @param random IV 来源，每个加密单元取 16 字节。
 * @param videoSink 接收解密后的 H.264 访问单元，缓冲区复用规则见 [AccessUnit]。
 * @param timeZone 控制消息 Date 头使用的时区，默认系统时区。
 */
class HiSightReceiver(
    sessionKey: ByteArray,
    private val config: HiSightConfig,
    private val random: SecureRandom,
    videoSink: AccessUnitSink,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    private val key: ByteArray

    init {
        require(sessionKey.size == AesCtr.KEY_BYTES) { "sessionKey 必须是 16 字节" }
        key = sessionKey.copyOf()
    }

    // 控制连接（controlLock）
    private val controlLock = Any()
    private val controlCipher = AesCtr(key)
    private val decoder = ControlFrameDecoder()
    private val capabilities = Capabilities(config)
    private val pending = HashMap<Int, String>()
    private val carryOver = ArrayList<HiSightOutput>()
    private var started = false
    private var cryptoReady = false
    private var optionsReceived = false

    /**
     * 这台手机在控制连接上实际发过哪些请求（按顺序，去重），只用来在出错提示里说明「走到哪一步」。
     * 鸿蒙各版本走的步骤并不一致，有这个才不用抓包就能判断卡在哪。
     */
    private val seenRequests = ArrayList<String>()
    private var lastCseq = 0
    private var setup: MediaSetup? = null
    private var videoConnected = false
    private var remoteConnected = false
    private var playRequested = false
    private var controlEof = false
    private var failed = false

    @Volatile
    private var ended = false

    // 视频连接（videoLock）
    private val videoLock = Any()
    private val video = VideoDepacketizer(key, videoSink)

    // 回控连接（inputLock；加锁顺序只能是 controlLock → inputLock）
    private val inputLock = Any()
    private var encoder: UibcEncoder? = null
    private var remoteOpen = false

    /** 控制连接已连上：返回首发的明文 ANNOUNCE。只能调用一次。 */
    fun start(): List<HiSightOutput> {
        synchronized(controlLock) {
            check(!started) { "start() 只能调用一次" }
            started = true
            if (ended) return emptyList()
            lastCseq = 1
            pending[1] = ANNOUNCE
            return listOf(sendControl(RtspTemplates.ANNOUNCE))
        }
    }

    /**
     * 控制连接收到的字节。[wallClockMillis] 是当前墙上时间（`System.currentTimeMillis()`），
     * 只用来生成 Date 头。会话结束或失败后再喂的数据会被忽略。
     */
    fun feedControl(data: ByteArray, offset: Int, length: Int, wallClockMillis: Long): List<HiSightOutput> {
        checkRange(data, offset, length)
        synchronized(controlLock) {
            check(started) { "先调用 start()" }
            if (ended || failed) return emptyList()
            val out = ArrayList<HiSightOutput>()
            try {
                decoder.append(data, offset, length)
                while (!ended) {
                    val payload = decoder.next() ?: break
                    val plain = if (cryptoReady) controlCipher.open(payload) else payload
                    handle(RtspParser.parse(plain), out, wallClockMillis)
                }
            } catch (e: Exception) {
                failed = true
                carryOver.addAll(out)
                throw e
            }
            return out
        }
    }

    /** 控制连接被对端关闭。残留半帧时抛异常（之后调用 [shutdown]）；否则结束会话，不再发 TEARDOWN。 */
    fun onControlClosed(): List<HiSightOutput> {
        synchronized(controlLock) {
            if (ended) return emptyList()
            if (decoder.hasPartial && !failed) {
                failed = true
                throw MalformedMessageException("控制连接关闭时仍有未收完的帧")
            }
            controlEof = true
            val out = takeCarryOver()
            terminate(HiSightEndReason.CONTROL_CLOSED, out, sendTeardown = false, releaseInputs = true)
            return out
        }
    }

    /** 视频连接已连上。若回控连接不需要或也已就绪，返回 PLAY。 */
    fun onVideoConnected(): List<HiSightOutput> {
        synchronized(controlLock) {
            if (ended || failed) return emptyList()
            checkNotNull(setup) { "还没有收到 SETUP 应答" }
            check(!videoConnected) { "重复通知视频已连接" }
            videoConnected = true
            return maybePlay()
        }
    }

    /** 回控连接已连上。若视频连接也已就绪，返回 PLAY。 */
    fun onRemoteControlConnected(): List<HiSightOutput> {
        synchronized(controlLock) {
            if (ended || failed) return emptyList()
            val media = checkNotNull(setup) { "还没有收到 SETUP 应答" }
            check(media.remoteControlPort != NO_PORT) { "手机没有给回控端口" }
            check(!remoteConnected) { "重复通知回控已连接" }
            remoteConnected = true
            synchronized(inputLock) { remoteOpen = true }
            return maybePlay()
        }
    }

    /**
     * 视频连接收到的字节。解出的每一帧同步交给 [AccessUnitSink]。
     * 数据不合法（含丢包、重复、SSRC 变化）时抛 [MalformedMessageException]，视频流不可再用。
     */
    fun feedVideo(data: ByteArray, offset: Int, length: Int) {
        checkRange(data, offset, length)
        if (ended) return
        synchronized(videoLock) { video.feed(data, offset, length) }
    }

    /** 视频连接被对端关闭：检查没有残帧后正常结束会话（释放按下的输入、发 TEARDOWN）。 */
    fun onVideoClosed(): List<HiSightOutput> {
        if (ended) return emptyList()
        synchronized(videoLock) { video.finish() }
        synchronized(controlLock) {
            if (ended) return emptyList()
            val out = takeCarryOver()
            terminate(HiSightEndReason.VIDEO_CLOSED, out, sendTeardown = true, releaseInputs = true)
            return out
        }
    }

    /** 回控连接收到的字节。协议没有定义这个方向的数据，收到即抛异常。 */
    fun feedRemoteControl(data: ByteArray, offset: Int, length: Int) {
        checkRange(data, offset, length)
        if (length == 0 || ended) return
        throw MalformedMessageException("回控连接收到未定义的入站数据（$length 字节）")
    }

    /** 回控连接被关闭（对端关闭或本机发送失败）：结束会话并发 TEARDOWN。 */
    fun onRemoteControlClosed(): List<HiSightOutput> {
        synchronized(controlLock) {
            if (ended) return emptyList()
            synchronized(inputLock) {
                encoder?.clear()
                remoteOpen = false
            }
            val out = takeCarryOver()
            terminate(HiSightEndReason.REMOTE_CONTROL_CLOSED, out, sendTeardown = true, releaseInputs = false)
            return out
        }
    }

    /**
     * 编码一次输入，返回要写到回控连接的包。回控连接还没就绪或会话已结束时返回空列表（输入被丢弃）。
     * 事件不合法或未经本次协商时抛 [IllegalArgumentException]，动作顺序错误时抛 [IllegalStateException]，
     * 两者都不改变已按下的状态。返回的包必须按顺序发出；发送失败时调用 [onRemoteControlClosed]。
     */
    fun sendInput(input: HiSightInput): List<HiSightOutput> {
        check(config.touchInput || config.keyInput || config.mediaKeyInput || config.textInput) { "配置没有启用任何输入" }
        synchronized(inputLock) {
            val enc = encoder
            if (enc == null || !remoteOpen || ended) return emptyList()
            return listOf(HiSightOutput.Send(HiSightChannel.REMOTE_CONTROL, enc.encode(input)))
        }
    }

    /** 抬起所有仍按下的触点和按键（例如窗口失焦）。正常结束时 [shutdown] 会自动做这一步。 */
    fun releaseInputs(): List<HiSightOutput> {
        synchronized(inputLock) {
            val enc = encoder
            if (enc == null || !remoteOpen || ended) return emptyList()
            return enc.release().map { HiSightOutput.Send(HiSightChannel.REMOTE_CONTROL, it) }
        }
    }

    /**
     * 本机结束会话：先补发出错前未交出的应答，再释放按下的输入，加密已协商且控制连接未断时发一次
     * TEARDOWN（不等手机确认），最后关闭全部连接。可重复调用，之后返回空列表。
     */
    fun shutdown(): List<HiSightOutput> {
        synchronized(controlLock) {
            val out = takeCarryOver()
            terminate(HiSightEndReason.LOCAL_SHUTDOWN, out, sendTeardown = true, releaseInputs = true)
            return out
        }
    }

    private fun handle(message: RtspMessage, out: MutableList<HiSightOutput>, wallClockMillis: Long) {
        if (message.isResponse) {
            handleResponse(message, out)
            return
        }
        val method = RtspParser.requestMethod(message.firstLine)
        if (method !in seenRequests) seenRequests += method
        when (method) {
            "ANNOUNCE" -> handleAnnounce(message, out, wallClockMillis)
            "OPTIONS" -> handleOptions(message, out, wallClockMillis)
            "GET_PARAMETER" -> handleGetParameter(message, out)
            "SET_PARAMETER" -> handleSetParameter(message, out, wallClockMillis)
            // PAUSE、手机直接发的 TEARDOWN 等请求不在已知的交互里，不猜应答。
            else -> throw MalformedMessageException("收到未实现的控制请求（已收到：${trace()}）")
        }
    }

    private fun handleResponse(message: RtspMessage, out: MutableList<HiSightOutput>) {
        if (message.firstLine != "RTSP/1.0 200 OK" || message.body.isNotEmpty()) {
            throw MalformedMessageException("手机应答不是空正文的 200")
        }
        when (pending.remove(message.cseq) ?: throw MalformedMessageException("手机应答的 CSeq 对不上待应答的请求")) {
            SETUP -> handleSetupResponse(message, out)
            PLAY -> out += HiSightOutput.PlayAccepted
        }
    }

    /**
     * 手机的 ANNOUNCE 选定加密方式；明文回 200 之后两个方向都切到 AES-CTR。
     *
     * 各家手机系统的写法并不一致（鸿蒙 4.2 和 HarmonyOS NEXT 就不同），所以只校验真正决定加密结果的
     * 那两件事，不限制行的数量与行尾写法：
     * 1. 能找到 `encrypt_description: …` 这一行（行尾 CRLF、裸 LF、裸 CR 都认，前后允许多余空行）；
     *    有的系统干脆省掉这个前缀，那就退一步按整个正文找字段。
     * 2. 里面 `encrypt_list` 含 `aes128ctr`、`version` 是 `1`（`1.0` 也认）。
     * 行里附带的其他字段、值两端的引号一律忽略，不因为写法不同就断开。
     */
    private fun handleAnnounce(message: RtspMessage, out: MutableList<HiSightOutput>, wallClockMillis: Long) {
        if (cryptoReady) throw MalformedMessageException("ANNOUNCE 重复")
        if (message.body.any { it < 0 }) throw MalformedMessageException("加密选择正文含非 ASCII 字节")
        val text = String(message.body, Charsets.US_ASCII)
        val fields = LinkedHashMap<String, String>()
        collectFields(encryptDescriptionValue(text) ?: text, fields)
        val list = fields["encrypt_list"]
            ?: throw MalformedMessageException("加密选择里没有 encrypt_list（正文：${excerpt(text)}）")
        if (list.split(',', ' ', '\t', ';').none { it.equals("aes128ctr", ignoreCase = true) }) {
            throw MalformedMessageException("加密选择里没有 aes128ctr（正文：${excerpt(text)}）")
        }
        val version = fields["version"]
            ?: throw MalformedMessageException("加密选择缺少 version（正文：${excerpt(text)}）")
        if (version != "1" && version != "1.0") {
            throw MalformedMessageException("只支持 version 1 的加密选择（正文：${excerpt(text)}）")
        }
        out += sendControl(RtspTemplates.ok(message.cseq, date(wallClockMillis)))
        cryptoReady = true
    }

    /** 把 `名字=值;名字=值` 收进 [fields]；名字转小写，值去掉两端空白和成对引号，重名的保留第一个。 */
    private fun collectFields(text: String, fields: MutableMap<String, String>) {
        for (item in text.split(';', '\r', '\n')) {
            val eq = item.indexOf('=')
            if (eq <= 0) continue
            val name = ParameterText.trimSpaceTab(item.substring(0, eq)).lowercase()
            if (name.isEmpty() || name in fields) continue
            fields[name] = unquote(ParameterText.trimSpaceTab(item.substring(eq + 1)))
        }
    }

    private fun unquote(value: String): String {
        if (value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first()) {
            return value.substring(1, value.length - 1)
        }
        return value
    }

    /** 找出 `encrypt_description: …` 这一行的值；行尾 CRLF、裸 LF、裸 CR 都认。 */
    private fun encryptDescriptionValue(text: String): String? {
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' || c == '\n') {
                encryptDescriptionIn(text.substring(start, i))?.let { return it }
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        return encryptDescriptionIn(text.substring(start))
    }

    private fun encryptDescriptionIn(line: String): String? {
        val trimmed = ParameterText.trimSpaceTab(line)
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return null
        if (ParameterText.trimSpaceTab(trimmed.substring(0, colon)).lowercase() != "encrypt_description") return null
        return trimmed.substring(colon + 1)
    }

    /** 出错时附在提示里的正文摘要：只留可见 ASCII、过长截断。加密选择里只有算法名，不含密钥。 */
    private fun excerpt(text: String): String {
        val out = StringBuilder()
        for (c in text) {
            if (out.length >= 120) {
                out.append("...")
                break
            }
            out.append(if (c in ' '..'~') c else '.')
        }
        return if (out.isEmpty()) "空" else out.toString()
    }

    private fun handleOptions(message: RtspMessage, out: MutableList<HiSightOutput>, wallClockMillis: Long) {
        if (!cryptoReady || message.body.isNotEmpty() || optionsReceived) {
            throw MalformedMessageException("OPTIONS 早于加密协商、带正文或重复（已收到：${trace()}）")
        }
        val date = date(wallClockMillis)
        out += sendControl(RtspTemplates.ok(message.cseq, date, withPublic = true))
        val cseq = nextCseq()
        out += sendControl(RtspTemplates.options(cseq, date))
        pending[cseq] = OPTIONS
        optionsReceived = true
    }

    /** 能力查询；空正文的 GET 是手机的保活，同样回 200。 */
    private fun handleGetParameter(message: RtspMessage, out: MutableList<HiSightOutput>) {
        requireNegotiationReady(message)
        val body = capabilities.responseBody(message.body)
        out += sendControl(RtspTemplates.getParameterOk(message.cseq, body))
    }

    private fun handleSetParameter(message: RtspMessage, out: MutableList<HiSightOutput>, wallClockMillis: Long) {
        // 带 module_id 的 SET_PARAMETER 是手机发给某个子模块的（实测鸿蒙 4.2 的「智慧聆听」用 1001），
        // 不是能力协商。本端不实现任何子模块，但**必须回 200**：不回手机就会一直等，最后把整条投屏断掉。
        // 回 200 只是让手机继续走，本端不解析也不启用任何对应功能。
        val routed = ParameterText.routedModuleId(message.body)
        if (routed != null) {
            out += sendControl(RtspTemplates.ok(message.cseq, date(wallClockMillis)))
            return
        }
        val fields = ParameterText.parameters(message.body)
        val trigger = fields[ParameterText.TRIGGER]
        if (trigger == "TEARDOWN") {
            if (!cryptoReady) throw MalformedMessageException("结束触发早于加密协商")
            if (!message.isTextParameters || fields.size != 1) {
                throw MalformedMessageException("结束触发必须是单独的 text/parameters 正文")
            }
            out += sendControl(RtspTemplates.ok(message.cseq, date(wallClockMillis)))
            terminate(HiSightEndReason.PEER_TEARDOWN, out, sendTeardown = true, releaseInputs = true)
            return
        }
        requireNegotiationReady(message)
        if (trigger == null) {
            if (setup != null || SETUP in pending.values) throw MalformedMessageException("SETUP 之后不支持重新协商能力")
            capabilities.select(fields)
            out += sendControl(RtspTemplates.ok(message.cseq, date(wallClockMillis)))
            return
        }
        if (trigger != "SETUP" || fields.size != 1) throw MalformedMessageException("只支持单独的 SETUP 触发")
        if (setup != null || SETUP in pending.values) throw MalformedMessageException("重复的 SETUP 触发")
        if (capabilities.selection == null) throw MalformedMessageException("SETUP 前缺少视频参数选择")
        out += sendControl(RtspTemplates.ok(message.cseq, date(wallClockMillis)))
        val cseq = nextCseq()
        out += sendControl(RtspTemplates.setup(cseq))
        pending[cseq] = SETUP
    }

    private fun handleSetupResponse(message: RtspMessage, out: MutableList<HiSightOutput>) {
        val ports = parseTransport(message.header("transport") ?: "")
        val media = capabilities.mediaSetup(ports.serverPort, ports.remoteControlPort)
        setup = media
        if (media.inputEvents.isNotEmpty() || media.inputVendors.isNotEmpty()) {
            synchronized(inputLock) {
                encoder = UibcEncoder(
                    key, random, media.inputEvents.toSet(), media.inputVendors.toSet(), media.sourceDeviceType,
                )
            }
        }
        out += HiSightOutput.StreamNegotiated(
            width = media.selection.width,
            height = media.selection.height,
            fps = media.selection.fps,
            touchInput = media.inputEvents.containsAll(Capabilities.TOUCH_EVENTS),
            keyInput = media.inputEvents.containsAll(Capabilities.KEY_EVENTS),
            textInput = Capabilities.TEXT_VENDOR in media.inputVendors,
        )
        out += HiSightOutput.Connect(HiSightChannel.VIDEO, media.videoPort)
        if (media.remoteControlPort != NO_PORT) {
            out += HiSightOutput.Connect(HiSightChannel.REMOTE_CONTROL, media.remoteControlPort)
        }
    }

    private fun maybePlay(): List<HiSightOutput> {
        val media = setup ?: return emptyList()
        val remoteReady = media.remoteControlPort == NO_PORT || remoteConnected
        if (!videoConnected || !remoteReady || playRequested || controlEof) return emptyList()
        playRequested = true
        val cseq = nextCseq()
        pending[cseq] = PLAY
        return listOf(sendControl(RtspTemplates.play(cseq)))
    }

    private fun terminate(
        reason: HiSightEndReason,
        out: MutableList<HiSightOutput>,
        sendTeardown: Boolean,
        releaseInputs: Boolean,
    ) {
        if (ended) return
        ended = true
        synchronized(inputLock) {
            val enc = encoder
            if (enc != null && releaseInputs && remoteOpen) {
                for (packet in enc.release()) out += HiSightOutput.Send(HiSightChannel.REMOTE_CONTROL, packet)
            }
            enc?.clear()
            remoteOpen = false
        }
        if (sendTeardown && cryptoReady && !controlEof) {
            out += sendControl(RtspTemplates.teardown(nextCseq()))
        }
        out += HiSightOutput.CloseAll
        out += HiSightOutput.Ended(reason)
    }

    /**
     * 能力请求的前提只有「加密已协商好」。
     *
     * 原来还要求必须先收到 OPTIONS，但鸿蒙 4.2 实测不先发 OPTIONS 就查能力；OPTIONS 那一步只是互相
     * 告知支持的方法，跳过它不影响加密和能力协商的结果，所以不再强制，收到就应答、没收到也不拦。
     */
    private fun requireNegotiationReady(message: RtspMessage) {
        if (!cryptoReady) throw MalformedMessageException("能力请求早于加密协商（已收到：${trace()}）")
        if (message.body.isNotEmpty() && !message.isTextParameters) {
            throw MalformedMessageException("能力正文缺少 text/parameters 类型（已收到：${trace()}）")
        }
    }

    /** 已收到的请求序列，出错提示里用来说明走到哪一步。 */
    private fun trace(): String = if (seenRequests.isEmpty()) "只有应答" else seenRequests.joinToString(" → ")

    private fun sendControl(text: String): HiSightOutput.Send {
        val plain = text.toByteArray(Charsets.US_ASCII)
        val payload = if (cryptoReady) controlCipher.seal(plain, random) else plain
        return HiSightOutput.Send(HiSightChannel.CONTROL, ControlFraming.encode(payload))
    }

    private fun takeCarryOver(): MutableList<HiSightOutput> {
        val out = ArrayList<HiSightOutput>(carryOver)
        carryOver.clear()
        return out
    }

    private fun nextCseq(): Int = ++lastCseq

    private fun date(wallClockMillis: Long) = RtspTemplates.date(wallClockMillis, timeZone)

    private fun checkRange(data: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "范围非法" }
    }

    private companion object {
        const val ANNOUNCE = "ANNOUNCE"
        const val OPTIONS = "OPTIONS"
        const val SETUP = "SETUP"
        const val PLAY = "PLAY"
        const val NO_PORT = -1
    }
}
