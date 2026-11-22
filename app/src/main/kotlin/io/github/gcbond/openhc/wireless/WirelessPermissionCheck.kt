package io.github.gcbond.openhc.wireless

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 判断无线连接要的权限有没有给（安卓类，接线用；规则在纯逻辑的 [WirelessPermissions]）。
 *
 * 普通权限用 `checkSelfPermission`；[WirelessPermissions.WRITE_SETTINGS] 例外：它是 appop 权限
 * （`signature|preinstalled|appop|pre23`），安卓 6.0 起 `checkSelfPermission` 对它一律返回拒绝，
 * 必须用 `Settings.System.canWrite` 判断（就是系统设置页那个开关）。
 */
object WirelessPermissionCheck {
    fun granted(context: Context, permission: String): Boolean = when {
        permission == WirelessPermissions.WRITE_SETTINGS -> canWriteSettings(context)
        else -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** 现在有没有「修改系统设置」。安卓 6.0 以下这个权限安装即授予，读不到 appop 时按有算。 */
    fun canWriteSettings(context: Context): Boolean = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        true
    } else {
        try {
            Settings.System.canWrite(context)
        } catch (e: RuntimeException) {
            false
        }
    }
}
