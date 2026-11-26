package io.github.gcbond.openhc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import io.github.gcbond.openhc.audio.MicPermission
import io.github.gcbond.openhc.connection.ConnectionController
import io.github.gcbond.openhc.connection.DeviceInfo
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.projection.ProjectionView
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.ClarityPreferences
import io.github.gcbond.openhc.settings.LogExport
import io.github.gcbond.openhc.settings.MediaKeyPreferences
import io.github.gcbond.openhc.settings.PendingSetting
import io.github.gcbond.openhc.settings.ReconnectReminder
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.BootStartPolicy
import io.github.gcbond.openhc.ui.HomeActions
import io.github.gcbond.openhc.ui.HomeView
import io.github.gcbond.openhc.ui.SettingsView
import io.github.gcbond.openhc.ui.WirelessActions
import io.github.gcbond.openhc.wireless.WirelessController
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessStartFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 唯一的界面：首页、设置、全屏投屏页。连接本身在 [ConnectionController] 和前台服务里，界面关掉不影响。
 *
 * android4-support 分支：从 ComponentActivity + Compose 改成系统 [Activity] + 原生 View。
 * 状态仍由 StateFlow 推过来，这里订阅后在主线程重画当前页面。
 * 权限申请从 registerForActivityResult 改成 requestPermissions / startActivityForResult（兼容安卓 4.1）。
 *
 * 插线拉起、开机自动打开、开机静默连接的逻辑与原版一致，见各方法上的说明。
 */
class MainActivity : Activity() {

    /**
     * 开始有线的来由。START：点了按钮；BOOT：开机等插线；USB_ATTACHED：系统按「设备接入」拉起（闸门关着的重新枚举也会来）；
     * PLUGGED：确认真插上了手机（界面开着时的设备接入、或回到前台时手机插着），插线优先。
     */
    private enum class StartKind { START, USB_ATTACHED, BOOT, PLUGGED }

    private enum class Screen { HOME, SETTINGS, PROJECTION }

    private lateinit var settings: AppSettings
    private var pendingStart: StartKind? = null

    /** 进设置页时「下次连接才生效」各项的值；回首页时对比，正在进行的连接用不上的改动弹框提醒。 */
    private var settingsOnEntry: Map<PendingSetting, String>? = null

    private val controller: ConnectionController get() = (application as OpenHcApp).connection
    private val wireless: WirelessController get() = (application as OpenHcApp).wireless

    private lateinit var wirelessStart: WirelessStartFlow

    private lateinit var root: FrameLayout
    private var homeView: HomeView? = null
    private var settingsView: SettingsView? = null
    private var projectionView: ProjectionView? = null

    private var screen = Screen.HOME
    private var projectionWireless = false
    private var autoShownAttempt = 0
    private var autoShownWireless = 0

    private lateinit var identity: IdentityLoader.Result
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        identity = IdentityLoader.load(this)
        wirelessStart = WirelessStartFlow(this) { wireless }

        root = FrameLayout(this).apply { setBackgroundColor(io.github.gcbond.openhc.ui.Palette.Background) }
        setContentView(root)

        if (savedInstanceState == null) handleLaunch(intent)
        observe()
    }

    /**
     * 订阅三份状态（有线、无线、各自的解码器与触摸重置计数）。
     * 每次变化都重画当前页面：页面结构简单，整体重建比做增量更新可靠，也不会有漏更新。
     */
    private fun observe() {
        val j = Job()
        job = j
        val s = CoroutineScope(Dispatchers.Main + j)
        scope = s
        s.launch { controller.state.collect { renderCurrent() } }
        s.launch { wireless.state.collect { renderCurrent() } }
        s.launch { controller.decoder.collect { renderCurrent() } }
        s.launch { wireless.decoder.collect { renderCurrent() } }
        s.launch { controller.touchResets.collect { renderCurrent() } }
        s.launch { wireless.touchResets.collect { renderCurrent() } }
        renderCurrent()
    }

    override fun onDestroy() {
        job?.cancel()
        projectionView?.attachDecoder(null)
        super.onDestroy()
    }

    /**
     * 决定现在显示哪一页。
     *
     * 自动切投屏：任何一边进入投屏就切到投屏页；用户按返回回首页后，同一次连接不再强拉回去（用 attempt 记）。
     * 投屏结束回首页；要输连接码时也从设置页回首页。
     */
    private fun renderCurrent() {
        val wired = controller.state.value
        val w = wireless.state.value

        if (w.phase == WirelessPhase.PROJECTING && autoShownWireless != w.attempt) {
            autoShownWireless = w.attempt
            projectionWireless = true
            screen = Screen.PROJECTION
        }
        if (wired.phase == Phase.PROJECTING && autoShownAttempt != wired.attempt) {
            autoShownAttempt = wired.attempt
            projectionWireless = false
            screen = Screen.PROJECTION
        }
        if (screen == Screen.PROJECTION) {
            val still = if (projectionWireless) w.phase == WirelessPhase.PROJECTING else wired.phase == Phase.PROJECTING
            if (!still) screen = Screen.HOME
        }
        if (wired.phase == Phase.PAIRING && screen == Screen.SETTINGS) screen = Screen.HOME

        when (screen) {
            Screen.HOME -> showHome(wired, w)
            Screen.SETTINGS -> showSettings()
            Screen.PROJECTION -> showProjection()
        }
    }

    private fun showHome(
        wired: io.github.gcbond.openhc.connection.ConnectionUiState,
        w: io.github.gcbond.openhc.wireless.WirelessUiState,
    ) {
        detachProjection()
        val view = homeView ?: HomeView(this).also { homeView = it }
        view.render(
            wired = wired,
            wireless = w,
            identity = identity,
            actions = HomeActions(
                onStart = { requestStart(StartKind.START) },
                onStop = { controller.stop() },
                onRetryNow = { controller.retryNow() },
                onGrantVpn = { requestStart(StartKind.START) },
                onResetTrustStore = { controller.resetTrustStoreAndStart() },
                phonePlugged = { controller.phonePlugged() },
            ),
            wirelessActions = WirelessActions(
                onStart = { wirelessStart.start() },
                onStop = { wireless.stop() },
                onShowProjection = {
                    if (w.phase == WirelessPhase.PROJECTING) {
                        projectionWireless = true
                        screen = Screen.PROJECTION
                        renderCurrent()
                    }
                },
                onOpenSettings = ::openSettings,
                onOpenAppSettings = { wirelessStart.openAppSettings() },
                onEnableBluetooth = { wirelessStart.requestEnableBluetooth() },
                onResetTrustStore = { wireless.resetTrustStoreAndStart() },
            ),
            onShowProjection = {
                if (wired.phase == Phase.PROJECTING) {
                    projectionWireless = false
                    screen = Screen.PROJECTION
                    renderCurrent()
                }
            },
            onOpenSettings = ::openSettings,
        )
        setContent(view)
    }

    /** 打开设置页：先记下「下次连接才生效」各项此刻的值，回来时对比。 */
    private fun openSettings() {
        settingsOnEntry = pendingSettingsSnapshot()
        screen = Screen.SETTINGS
        renderCurrent()
    }

    /**
     * 从设置页回首页：有连接正用着旧设置（已经连上，或者已经开始、还在等手机）时，把改了、但要重新连接才生效的项列出来
     * 提醒一下；没在连接时下一次连接自然按新设置，不提醒。
     */
    private fun closeSettings() {
        val before = settingsOnEntry
        settingsOnEntry = null
        wireless.onSettingsClosed()
        screen = Screen.HOME
        renderCurrent()
        if (before == null) return
        val app = application as OpenHcApp
        val wired = app.connection.state.value
        val w = app.wireless.state.value
        val changed = ReconnectReminder.changed(
            before,
            pendingSettingsSnapshot(),
            wiredActive = ReconnectReminder.wiredUsesOldSettings(wired.phase),
            wirelessActive = ReconnectReminder.wirelessUsesOldSettings(w.phase),
        )
        if (changed.isNotEmpty()) showReconnectReminder(changed, w.phase == WirelessPhase.PROJECTING)
    }

    /** 「下次连接才生效」各项的当前值。键和设置页上显示的名字对得上。 */
    private fun pendingSettingsSnapshot(): Map<PendingSetting, String> {
        val audio = AudioPreferences(this)
        val wirelessPrefs = WirelessPreferences(this)
        return PendingSetting.values().associateWith { key ->
            when (key) {
                PendingSetting.FPS -> settings.fps.toString()
                PendingSetting.UI_SCALE -> settings.uiScalePercent.toString()
                PendingSetting.CLARITY -> ClarityPreferences(this).clarity.name
                PendingSetting.FULL_SCREEN -> settings.fullScreen.toString()
                PendingSetting.MEDIA_KEYS -> MediaKeyPreferences(this).enabled.toString()
                PendingSetting.BLUETOOTH_ADDRESS -> settings.manualBluetoothAddress.orEmpty()
                PendingSetting.AUDIO_ENABLED -> audio.enabled.toString()
                PendingSetting.AUDIO_DELAY -> audio.delayMillis.toString()
                PendingSetting.NAVIGATION_STREAM -> audio.navigationStream.toString()
                PendingSetting.MEDIA_STREAM -> audio.mediaStream.toString()
                PendingSetting.WIRELESS_MODE -> wirelessPrefs.carHotspot.toString()
                PendingSetting.CAR_HOTSPOT_NAME -> wirelessPrefs.carHotspotName
                PendingSetting.CAR_HOTSPOT_PASSWORD -> wirelessPrefs.carHotspotPassword
                PendingSetting.CAR_HOTSPOT_BAND -> wirelessPrefs.carHotspotFiveGhz.toString()
            }
        }
    }

    /** 设置页上这一项的名字。 */
    private fun PendingSetting.label(): Int = when (this) {
        PendingSetting.FPS -> R.string.settings_fps
        PendingSetting.UI_SCALE -> R.string.settings_ui_scale
        PendingSetting.CLARITY -> R.string.settings_clarity
        PendingSetting.FULL_SCREEN -> R.string.settings_full_screen
        PendingSetting.MEDIA_KEYS -> R.string.settings_media_keys
        PendingSetting.BLUETOOTH_ADDRESS -> R.string.settings_bt
        PendingSetting.AUDIO_ENABLED -> R.string.settings_audio_enabled
        PendingSetting.AUDIO_DELAY -> R.string.settings_audio_delay
        PendingSetting.NAVIGATION_STREAM -> R.string.settings_audio_navigation_stream
        PendingSetting.MEDIA_STREAM -> R.string.settings_audio_media_stream
        // android4 分支没有 main 的三种无线方式，只有「车机自带热点」一个开关，名字用它的。
        PendingSetting.WIRELESS_MODE -> R.string.settings_car_hotspot
        PendingSetting.CAR_HOTSPOT_NAME -> R.string.settings_car_hotspot_name
        PendingSetting.CAR_HOTSPOT_PASSWORD -> R.string.settings_car_hotspot_password
        PendingSetting.CAR_HOTSPOT_BAND -> R.string.settings_car_hotspot_5g
    }

    /** 列出改了、但要重新连接才生效的项。 */
    private fun showReconnectReminder(changed: List<PendingSetting>, projecting: Boolean) {
        val names = changed.joinToString("\n") { "· " + getString(it.label()) }
        val message = getString(R.string.settings_reconnect_reminder_message, names) +
            if (projecting) "\n\n" + getString(R.string.settings_reconnect_reminder_projection) else ""
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_reconnect_reminder_title)
            .setMessage(message)
            .setPositiveButton(R.string.settings_reconnect_reminder_ok, null)
            .show()
    }

    private fun showSettings() {
        detachProjection()
        val view = settingsView ?: SettingsView(this).also { settingsView = it }
        view.render(
            settings = settings,
            systemBluetoothAddress = DeviceInfo.systemBluetoothAddress(this),
            // 「自动读取」按钮：用户手动触发，把所有来源的候选值都列出来让车主挑。
            onReadCandidates = { DeviceInfo.bluetoothAddressCandidates(this) },
            // 设置页「让车机连这台手机」：先回首页（连接码、进度都在首页显示），再让车机主动拨这台手机。
            onReconnectPhone = { address ->
                screen = Screen.HOME
                renderCurrent()
                wirelessStart.reconnect(address)
            },
            onBack = {
                wireless.onSettingsClosed()
                screen = Screen.HOME
                renderCurrent()
            },
        )
        setContent(view)
    }

    private fun showProjection() {
        val decoder = if (projectionWireless) wireless.decoder.value else controller.decoder.value
        val state = if (projectionWireless) wireless.state.value.projection else controller.state.value
        val resets = if (projectionWireless) wireless.touchResets.value else controller.touchResets.value

        val view = projectionView ?: ProjectionView(this).also { projectionView = it }
        if (view.parent != null) (view.parent as ViewGroup).removeView(view)
        view.fullScreen = io.github.gcbond.openhc.connection.DisplayArea.activeFullScreen
        view.immersive = true
        view.setVideoSize(state.videoWidth, state.videoHeight)
        view.onInput = { input: HiSightInput ->
            if (projectionWireless) wireless.sendInput(input) else controller.sendInput(input)
        }
        view.attachDecoder(decoder)
        // touchResets 变了就重置一次触摸状态（发送端报告触摸对不上时）。
        if (view.tag != resets) {
            view.tag = resets
            view.resetTouches()
        }

        val frame = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val message = when {
            !state.firstFrame -> getString(R.string.projection_waiting_frame)
            state.decoderProblem != null -> getString(R.string.projection_decoder_problem, state.decoderProblem)
            else -> ""
        }
        if (message.isNotEmpty()) {
            frame.addView(
                TextView(this).apply {
                    text = message
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 15f
                    setBackgroundColor(0xAA000000.toInt())
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(12)
                    topMargin = dp(12)
                },
            )
        }
        setContent(frame)
    }

    /** 换页时把投屏视图从窗口上摘下来（Surface 还给解码器前要先断开）。 */
    private fun detachProjection() {
        projectionView?.let { view ->
            if (view.parent != null) (view.parent as ViewGroup).removeView(view)
            view.attachDecoder(null)
        }
    }

    private fun setContent(view: android.view.View) {
        if (root.childCount == 1 && root.getChildAt(0) === view) return
        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        // 手机点「车机」图标暂停投屏时要回车机桌面，只在界面在前台时处理（见 CarHome）。
        io.github.gcbond.openhc.connection.CarHome.attach(this)
        // 记下底部导航栏的实际高度（不管此刻显不显示），「全屏显示」关时按它让出画面、算声明尺寸。
        root.post {
            val bar = navigationBarHeight()
            if (bar > 0) io.github.gcbond.openhc.connection.DisplayArea.measuredNavigationBarHeight = bar
        }
        wireless.onAppForeground()
        // 回到前台时手机插着、没在连接、也没在这次插入上点过停止：当作刚插上（插线优先）。
        // 还没同意过 VPN 授权时不自动弹授权框（拒绝后每次回到前台都会弹），等用户点首页的按钮。
        controller.startIfPlugged { if (VpnConsent.isGranted(this)) requestStart(StartKind.PLUGGED) }
    }

    override fun onPause() {
        io.github.gcbond.openhc.connection.CarHome.detach(this)
        super.onPause()
    }

    private fun navigationBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /**
     * 界面没处理的方向盘、媒体键交给 [io.github.gcbond.openhc.input.MediaKeys]：有能发键的连接时转给手机并吃掉，
     * 系统就不再把它派给别的媒体应用；没有连接时照常交给系统。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        io.github.gcbond.openhc.input.MediaKeys.onWindowKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        io.github.gcbond.openhc.input.MediaKeys.onWindowKey(event) || super.onKeyUp(keyCode, event)

    /**
     * 返回键：设置页、投屏页都回首页；投屏时连接保持，可以再回到投屏画面。
     *
     * android4-support 分支：安卓 4.x 没有 OnBackPressedDispatcher / 预测性返回，只能走这个旧回调。
     * lint 的 GestureBackNavigation 是针对新系统的提醒，这里按设计忽略。
     */
    @Deprecated("安卓 4.x 上没有 OnBackPressedDispatcher")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        when (screen) {
            Screen.SETTINGS -> closeSettings()
            Screen.PROJECTION -> {
                screen = Screen.HOME
                renderCurrent()
            }
            Screen.HOME -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    private fun handleLaunch(intent: Intent?) {
        if (BootStart.isBootLaunch(intent)) {
            if (BootStart.waitForCable(this)) {
                Log.i(TAG, "开机自动打开：开始等插线")
                requestStart(StartKind.BOOT)
            } else {
                Log.i(TAG, "开机自动打开：不等插线（插线时由系统拉起本应用再开始有线），无线自动连手机")
            }
        } else if (intent?.action == BootStartPolicy.ACTION_SHOW_PROJECTION) {
            Log.i(TAG, "开机静默连接：手机连上了，界面带到前台")
        } else {
            handleUsbAttach(intent)
        }
    }

    private fun handleUsbAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        // 任务最初是插线拉起的话，从「最近任务」重开时系统会把那次的 Intent 再给一遍，那不是插线。
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // 被「设备接入」拉起，说明用户在系统框里同意过用本应用打开手机：之后有线会话先等系统自动授权。
        settings.usbAttachAccepted = true
        Log.i(TAG, "插线拉起")
        // 这次「接入」是不是真的插上了手机（不是手机切换模式时的重新枚举）：真插上了就插线优先，让无线让出。
        controller.checkPlug { real ->
            if (real) requestStart(StartKind.PLUGGED) else requestStart(StartKind.USB_ATTACHED)
        }
    }

    private fun requestStart(kind: StartKind) {
        // 真插上了手机（插线优先）：手机已经经蓝牙连上无线就不管插线（多半是在充电），无线照常用；
        // 还在等手机（扫码、等回连、车机在拨）就让给有线。其它来由（点开始、开机等插线）照原规则：无线在进行就不开始。
        val blocked = if (kind == StartKind.PLUGGED) wireless.phoneOnWireless else wireless.blocksWired
        if (blocked) {
            // 两种连接同时只能有一种：插线拉起时只记一笔，用户点开始时给出原因。
            Log.i(TAG, "无线连接正在进行，不开始有线连接（$kind）")
            if (kind == StartKind.START) {
                controller.reportBlocked(Notice.StartFailed(getString(R.string.wireless_notice_wireless_active)))
            }
            return
        }
        pendingStart = kind
        askNextPermission()
    }

    /** 开始前的运行时权限按顺序问，每样只自动问一次；问完（同意与否）接着 [continueStart]。 */
    private fun askNextPermission() {
        if (pendingStart == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.notificationPermissionAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            settings.notificationPermissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            return
        }
        val audio = AudioPreferences(this)
        if (audio.micEnabled && !audio.micPermissionAsked && !MicPermission.granted(this)) {
            audio.micPermissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        continueStart()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_NOTIFICATION -> askNextPermission()
            REQ_MIC -> {
                if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "用户没有给录音权限：这次不声明车机麦克风")
                    Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
                }
                askNextPermission()
            }
            // 无线连接/车机主动拨号前申请的权限：给了就接着开始。
            WirelessStartFlow.REQUEST_CODE -> wirelessStart.onPermissionResult()
            // 设置页「补全手机蓝牙配对」申请的权限。
            SettingsView.REQ_LE_PAIRING -> settingsView?.onPermissionResult()
        }
    }

    private fun continueStart() {
        val kind = pendingStart ?: return
        when (val consent = VpnConsent.check(this)) {
            VpnConsent.Status.Granted -> {
                pendingStart = null
                dispatch(kind)
            }
            is VpnConsent.Status.NeedsUserConsent -> try {
                @Suppress("DEPRECATION")
                startActivityForResult(consent.intent, REQ_VPN)
            } catch (e: ActivityNotFoundException) {
                pendingStart = null
                controller.reportBlocked(Notice.VpnUnavailable(getString(R.string.vpn_dialog_missing)))
            }
            is VpnConsent.Status.Unavailable -> {
                pendingStart = null
                controller.reportBlocked(Notice.VpnUnavailable(consent.reason))
            }
        }
    }

    @Deprecated("安卓 4.x 上没有 Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 设置页「导出日志」：系统「另存为」界面选完目录回来，把日志写进去。
        if (requestCode == LogExport.REQ_SAVE) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            Toast.makeText(this, LogExport.finishSave(this, uri), Toast.LENGTH_LONG).show()
            return
        }
        if (requestCode != REQ_VPN) return
        val kind = pendingStart
        pendingStart = null
        if (kind == null) return
        if (resultCode == RESULT_OK && VpnConsent.isGranted(this)) {
            dispatch(kind)
        } else {
            Log.i(TAG, "用户没有同意 VPN 授权")
            controller.reportBlocked(Notice.VpnConsentRequired)
        }
    }

    private fun dispatch(kind: StartKind) {
        // 无线在等手机的话先让出：真插上了手机（插线优先）时扫码等待、车机在拨也让；其它来由只让自动进入的等回连。
        wireless.yieldToWired(plugged = kind == StartKind.PLUGGED)
        when (kind) {
            StartKind.START, StartKind.BOOT -> controller.start()
            StartKind.USB_ATTACHED, StartKind.PLUGGED -> controller.onUsbAttached()
        }
    }

    private companion object {
        const val TAG = "OpenHC-App"
        const val REQ_NOTIFICATION = 1001
        const val REQ_MIC = 1002
        const val REQ_VPN = 1003
    }
}
