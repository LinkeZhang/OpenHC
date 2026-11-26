package io.github.gcbond.openhc.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.connection.Step
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessUiState

/** 首页上和有线连接有关的操作。 */
internal class HomeActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onRetryNow: () -> Unit,
    val onGrantVpn: () -> Unit,
    val onResetTrustStore: () -> Unit,
    /** 车机上插着手机没在连（点过停止、还没同意 VPN 授权）：给出「连接插着的手机」。 */
    val phonePlugged: () -> Boolean = { false },
)

/** 首页左栏「无线连接」上的操作。 */
internal class WirelessActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onShowProjection: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onEnableBluetooth: () -> Unit,
    val onResetTrustStore: () -> Unit,
)

/** 二维码外面补的白边占边长的比例（约 2 个模块）。 */
private const val QR_EXTRA_QUIET = 0.04f

/**
 * 首页：左上应用名、右上「设置」；左栏无线（二维码、连接码），右栏有线（状态、提示、配对时的连接码）。
 *
 * android4-support 分支：改成普通 View 树。宽屏（[Dimens.WIDE_PAGE_MIN] 以上）排两栏，窄屏排一列整页滚动。
 * 每次状态变化都由 [HomeView.render] 重画整页 —— 状态是小对象、页面也不复杂，重建比做增量更新省事且不易出错。
 */
internal class HomeView(context: Context) : ScrollView(context) {

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(Palette.Background)
        isFillViewport = true
        addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun render(
        wired: ConnectionUiState,
        wireless: WirelessUiState,
        identity: IdentityLoader.Result,
        actions: HomeActions,
        wirelessActions: WirelessActions,
        onShowProjection: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        val prefs = WirelessPreferences(context)
        val identityLoaded = identity is IdentityLoader.Result.Loaded
        // 设置是在设置页里改的，回到首页时重新读，拿到的都是新值。
        val wirelessOff = prefs.wiredOnly && !wireless.active
        val wide = resources.displayMetrics.widthPixels >= context.dp(Dimens.WIDE_PAGE_MIN)
        val compact = resources.displayMetrics.heightPixels < context.dp(Dimens.CompactHeight)
        val side = context.dp(if (wide) Dimens.PageSide else Dimens.PageSideNarrow)

        root.removeAllViews()
        root.setPadding(side, 0, side, 0)

        root.addView(topBar(onOpenSettings))
        identityNotice(identity)?.let { root.addView(it) }

        val wirelessCard = wirelessCard(wireless, identityLoaded, wirelessOff, wirelessActions, compact)
        val wiredCard = wiredCard(wired, identityLoaded, actions, onShowProjection, compact)

        if (wide) {
            val columns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            columns.addView(
                wirelessCard,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f),
            )
            columns.addView(View(context), LinearLayout.LayoutParams(context.dp(24f), 1))
            columns.addView(
                wiredCard,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            root.addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8f)
            })
        } else {
            root.addView(wirelessCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8f)
            })
            root.addView(wiredCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(12f)
            })
        }

        root.addView(footer(prefs, compact), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(12f)
            bottomMargin = context.dp(16f)
        })
    }

    /** 顶栏：左边应用名，右上「设置」。 */
    private fun topBar(onOpenSettings: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(pageTitle(context, context.getString(R.string.app_name)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(topBarButton(context, context.getString(R.string.action_settings), onOpenSettings))
        return row
    }

    /** 缺连接参数时的提示卡片：醒目的提醒色底，正常时不显示。 */
    private fun identityNotice(identity: IdentityLoader.Result): View? {
        val text = when (identity) {
            IdentityLoader.Result.Missing -> context.getString(R.string.home_identity_missing)
            is IdentityLoader.Result.Invalid -> context.getString(R.string.home_identity_invalid, identity.reason)
            is IdentityLoader.Result.Loaded -> return null
        }
        val inner = context.column()
        inner.addView(context.textView(context.getString(R.string.home_identity_title), sizeSp = 16f, color = Palette.Warn, bold = true))
        inner.addView(
            context.textView(text, sizeSp = 13f, color = Palette.Text).apply {
                setPadding(0, context.dp(4f), 0, 0)
            },
        )
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundedBackground(0x29FFB74D, 10f)
            setPadding(context.dp(12f), context.dp(10f), context.dp(12f), context.dp(10f))
            addView(inner)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8f)
            }
        }
    }

    /** 左栏「无线连接」。 */
    private fun wirelessCard(
        state: WirelessUiState,
        identityLoaded: Boolean,
        wirelessOff: Boolean,
        actions: WirelessActions,
        compact: Boolean,
    ): View {
        val qr = state.qrCode.takeIf { state.phase == WirelessPhase.WAITING_FOR_SCAN }
        val problem = !wirelessOff && state.notice != null
        val status = if (wirelessOff) context.getString(R.string.home_wireless_off_title) else wirelessPhaseTitle(context, state)

        val inner = context.column()
        inner.addView(
            context.textView(
                context.getString(R.string.home_wireless_header, status),
                sizeSp = if (compact) 12f else 14f,
                color = if (problem) Palette.Warn else Palette.Muted,
            ),
        )
        inner.addView(View(context), LinearLayout.LayoutParams(1, context.dp(6f)))

        if (qr != null) {
            scanContent(inner, state, qr, actions, compact)
        } else {
            statusContent(inner, state, identityLoaded, wirelessOff, actions, compact)
        }
        return cardBox(context, inner)
    }

    /** 等扫码：说明、二维码、连接码、地址提示、底部状态和「停止」。 */
    private fun scanContent(
        inner: LinearLayout,
        state: WirelessUiState,
        qr: String,
        actions: WirelessActions,
        compact: Boolean,
    ) {
        inner.addView(rowDescription(context, wirelessHint(context, state)))

        val qrHolder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(8f), 0, context.dp(8f))
        }
        // 二维码按卡片宽度算，最大 260dp。
        val screenWidth = resources.displayMetrics.widthPixels
        val side = context.dp(if (screenWidth >= context.dp(Dimens.WIDE_PAGE_MIN)) Dimens.PageSide else Dimens.PageSideNarrow)
        val available = if (screenWidth >= context.dp(Dimens.WIDE_PAGE_MIN)) {
            ((screenWidth - side * 2 - context.dp(24f)) * 0.615f).toInt()
        } else {
            screenWidth - side * 2 - context.dp(32f)
        }
        val qrPx = minOf(available, context.dp(QrPinLayout.MAX_QR)).coerceAtLeast(context.dp(QrPinLayout.FLOOR_QR))
        val bitmap = QrCodes.bitmap(qr, qrPx)
        qrHolder.addView(ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.WHITE)
            // 老车机屏幕小，不做平滑，模块边缘保持锐利。
            (drawable as? android.graphics.drawable.BitmapDrawable)?.paint?.isFilterBitmap = false
        }, LinearLayout.LayoutParams(qrPx, qrPx).apply {
            setPadding(0, 0, 0, 0)
        })

        state.pinCode?.let { pin ->
            val (box, gap) = QrPinLayout.pinRow(
                qrPx / resources.displayMetrics.density,
                pin.length,
                if (compact) QrPinLayout.PIN_BOX_COMPACT else QrPinLayout.PIN_BOX,
            )
            if (box > 0f) {
                qrHolder.addView(View(context), LinearLayout.LayoutParams(1, context.dp(12f)))
                qrHolder.addView(pinBoxes(context, pin, box, gap))
            }
        }
        inner.addView(qrHolder)

        if (state.addressMismatch) {
            inner.addView(rowDescription(context, context.getString(R.string.wireless_warning_manual_address), warn = true))
        }
        state.bluetoothAddress?.let { address ->
            inner.addView(
                rowDescription(
                    context,
                    context.getString(
                        if (state.bluetoothSource == io.github.gcbond.openhc.connection.BluetoothSource.MANUAL) {
                            R.string.wireless_address_used_manual
                        } else {
                            R.string.wireless_address_used_system
                        },
                        address,
                    ),
                ),
            )
            inner.addView(rowDescription(context, context.getString(R.string.wireless_address_check_hint)))
        }
        state.notice?.let {
            inner.addView(rowDescription(context, wirelessNoticeText(context, it), warn = true))
        }

        // 底部一行：左边无线方式和广播情况，右边「停止」。
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = listOfNotNull(
            context.getString(R.string.wireless_network_line),
            state.beacon?.let { wirelessBeaconText(context, it) },
        ).joinToString("\n")
        bottom.addView(context.textView(info, sizeSp = 12f, color = Palette.Muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(
            pillButton(context, context.getString(R.string.action_stop), primary = false, enabled = state.phase != WirelessPhase.STOPPING, onClick = actions.onStop),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = context.dp(12f)
            },
        )
        inner.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(8f)
        })
    }

    /** 没有二维码时：居中说明、连接码、提示和按钮。 */
    private fun statusContent(
        inner: LinearLayout,
        state: WirelessUiState,
        identityLoaded: Boolean,
        wirelessOff: Boolean,
        actions: WirelessActions,
        compact: Boolean,
    ) {
        val message = if (wirelessOff) {
            context.getString(R.string.home_wireless_off_note)
        } else {
            wirelessHint(context, state)
        }
        inner.addView(
            context.textView(message, sizeSp = if (compact) 17f else 20f).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, context.dp(16f), 0, 0)
            },
        )
        if (state.phase == WirelessPhase.IDLE && !wirelessOff) {
            inner.addView(
                rowDescription(context, context.getString(R.string.wireless_tagline)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
        }
        if (!wirelessOff) {
            inner.addView(rowDescription(context, context.getString(R.string.wireless_network_line)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        state.pinCode?.let { pin ->
            val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density) - 64f
            val (box, gap) = QrPinLayout.pinRow(
                widthDp,
                pin.length,
                if (compact) QrPinLayout.PIN_BOX_COMPACT else QrPinLayout.PIN_BOX,
            )
            if (box > 0f) {
                inner.addView(pinBoxes(context, pin, box, gap), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(16f)
                })
            }
        }
        if (state.addressMismatch && state.phase != WirelessPhase.IDLE) {
            inner.addView(rowDescription(context, context.getString(R.string.wireless_warning_manual_address), warn = true))
        }
        state.notice?.takeUnless { wirelessOff && it == io.github.gcbond.openhc.wireless.WirelessNotice.WiredOnly }?.let {
            inner.addView(rowDescription(context, wirelessNoticeText(context, it), warn = true))
        }
        if (state.phase == WirelessPhase.IDLE && !wirelessOff) {
            state.autoHold?.let { hold ->
                wirelessAutoHoldText(context, hold)?.let { inner.addView(rowDescription(context, it)) }
            }
        }
        state.beacon?.let { wirelessBeaconText(context, it) }?.let { inner.addView(rowDescription(context, it)) }

        inner.addView(
            wirelessButtons(state, identityLoaded, wirelessOff, actions),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(16f)
                bottomMargin = context.dp(8f)
            },
        )
    }

    private fun wirelessButtons(
        state: WirelessUiState,
        identityLoaded: Boolean,
        wirelessOff: Boolean,
        actions: WirelessActions,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun add(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
            row.addView(
                pillButton(context, text, primary = primary, enabled = enabled, onClick = onClick),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (row.childCount > 0) leftMargin = context.dp(12f)
                },
            )
        }
        if (state.phase != WirelessPhase.IDLE) {
            if (state.phase == WirelessPhase.PROJECTING) {
                add(context.getString(R.string.action_show_projection), primary = true, onClick = actions.onShowProjection)
            }
            add(
                context.getString(R.string.action_stop),
                primary = false,
                enabled = state.phase != WirelessPhase.STOPPING,
                onClick = actions.onStop,
            )
            return row
        }
        if (wirelessOff) {
            add(context.getString(R.string.wireless_action_open_settings), primary = true, onClick = actions.onOpenSettings)
            return row
        }
        val start = context.getString(R.string.wireless_action_start)
        when (val notice = state.notice) {
            is io.github.gcbond.openhc.wireless.WirelessNotice.PermissionsDenied -> if (notice.permanently) {
                add(context.getString(R.string.wireless_action_app_settings), primary = true, onClick = actions.onOpenAppSettings)
                add(start, primary = false, onClick = actions.onStart)
            } else {
                add(context.getString(R.string.wireless_action_grant), primary = true, onClick = actions.onStart)
            }
            io.github.gcbond.openhc.wireless.WirelessNotice.BluetoothOff -> {
                add(context.getString(R.string.wireless_action_enable_bt), primary = true, onClick = actions.onEnableBluetooth)
                add(start, primary = false, onClick = actions.onStart)
            }
            io.github.gcbond.openhc.wireless.WirelessNotice.BluetoothAddressMissing ->
                add(context.getString(R.string.wireless_action_fill_bt), primary = true, onClick = actions.onOpenSettings)
            io.github.gcbond.openhc.wireless.WirelessNotice.WiredOnly ->
                add(context.getString(R.string.wireless_action_open_settings), primary = true, onClick = actions.onOpenSettings)
            is io.github.gcbond.openhc.wireless.WirelessNotice.TrustStoreCorrupt ->
                add(context.getString(R.string.action_reset_trust), primary = true, onClick = actions.onResetTrustStore)
            else -> add(start, primary = true, enabled = identityLoaded, onClick = actions.onStart)
        }
        return row
    }

    /** 右栏「有线连接」。 */
    private fun wiredCard(
        state: ConnectionUiState,
        identityLoaded: Boolean,
        actions: HomeActions,
        onShowProjection: () -> Unit,
        compact: Boolean,
    ): View {
        val warning = state.phase == Phase.USB_DEBUGGING_ON || state.phase == Phase.REPLUG_REQUIRED ||
            state.step == Step.PHONE_SWITCH_SLOW
        val inner = context.column()
        inner.addView(cardHeading(context, context.getString(R.string.home_wired_title)))
        inner.addView(
            context.textView(
                phaseTitle(context, state.phase),
                sizeSp = 18f,
                color = if (warning) Palette.Warn else Palette.Text,
                bold = true,
            ).apply { setPadding(0, context.dp(6f), 0, 0) },
        )
        inner.addView(
            context.textView(phaseHint(context, state), sizeSp = 13f, color = if (warning) Palette.Warn else Palette.Muted).apply {
                setPadding(0, context.dp(6f), 0, 0)
            },
        )
        val pin = state.pinCode
        if (state.phase == Phase.PAIRING && pin != null) {
            val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density) - 64f
            val (box, gap) = QrPinLayout.pinRow(
                widthDp,
                pin.length,
                if (compact) QrPinLayout.PIN_BOX_COMPACT else QrPinLayout.PIN_BOX,
            )
            if (box > 0f) {
                inner.addView(pinBoxes(context, pin, box, gap), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(12f)
                })
            }
        }
        state.notice?.let {
            inner.addView(
                rowDescription(context, noticeText(context, it), warn = true).apply {
                    setPadding(0, context.dp(6f), 0, 0)
                },
            )
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun add(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
            buttons.addView(
                pillButton(context, text, primary = primary, enabled = enabled, onClick = onClick),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (buttons.childCount > 0) leftMargin = context.dp(12f)
                },
            )
        }
        if (state.phase == Phase.IDLE) {
            when (state.notice) {
                Notice.VpnConsentRequired -> add(context.getString(R.string.action_grant_vpn), true, onClick = actions.onGrantVpn)
                is Notice.TrustStoreCorrupt -> add(context.getString(R.string.action_reset_trust), true, onClick = actions.onResetTrustStore)
                else -> {
                    add(context.getString(R.string.action_start), true, enabled = identityLoaded, onClick = actions.onStart)
                    // 手机已经插着却没在连（点过停止、或还没同意 VPN 授权）：给一个直接连它的按钮。
                    if (actions.phonePlugged()) {
                        add(context.getString(R.string.action_connect_plugged), false, enabled = identityLoaded, onClick = actions.onStart)
                    }
                }
            }
        } else {
            if (state.phase == Phase.PROJECTING) {
                add(context.getString(R.string.action_show_projection), true, onClick = onShowProjection)
            }
            if (state.phase == Phase.REPLUG_REQUIRED) {
                add(context.getString(R.string.action_retry_now), false, onClick = actions.onRetryNow)
            }
            add(context.getString(R.string.action_stop), false, enabled = state.phase != Phase.STOPPING, onClick = actions.onStop)
        }
        inner.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(14f)
        })
        return cardBox(context, inner)
    }

    /** 左下角：版本、当前模式。 */
    private fun footer(prefs: WirelessPreferences, compact: Boolean): View {
        val mode = if (prefs.wiredOnly) {
            context.getString(R.string.home_mode_wired_only)
        } else {
            val parts = mutableListOf(context.getString(R.string.home_mode_both))
            if (prefs.connectOnBluetooth) parts += context.getString(R.string.home_mode_connect_on_bt)
            parts.joinToString(" · ")
        }
        val text = context.getString(R.string.home_version, BuildConfig.VERSION_NAME) + "\n" +
            context.getString(R.string.home_mode, mode)
        return context.textView(text, sizeSp = if (compact) 11f else 12f, color = Palette.Muted)
    }
}
