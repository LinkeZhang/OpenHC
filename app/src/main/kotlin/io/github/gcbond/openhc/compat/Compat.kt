package io.github.gcbond.openhc.compat

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.Display
import java.io.File

/**
 * 安卓 4.1（API 16）兼容层。
 *
 * android4-support 分支把 minSdk 降到 16，主线是 23。原来代码里有一批 API 17～23 才有的调用，
 * 在 4.1 上会直接抛 NoSuchMethodError / NoClassDefFoundError 让应用闪退。这里按系统版本判断，
 * 低版本走能用的替代方案，高版本照原样。每个函数都写清了「为什么」和「低版本上退化成什么」。
 */
internal object Compat {

    /**
     * 不参与备份的应用私有目录。
     *
     * `Context.noBackupFilesDir` 是 API 21 才有。低版本上退回普通的 `filesDir`：
     * 安卓 4.1 的系统备份本来就很少用，而且这份数据（配对记录）丢了只是要重新配对，不影响使用。
     */
    fun noBackupDir(context: Context, name: String): File {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.noBackupFilesDir
        } else {
            context.filesDir
        }
        return File(base, name)
    }

    /** 查权限。安卓 6.0 以前权限是安装即授予，直接当作有。 */
    fun granted(context: Context, permission: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** 申请权限。安卓 6.0 以前没有运行时权限，什么都不做。 */
    fun requestPermissions(activity: Activity, permissions: Array<String>, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            activity.requestPermissions(permissions, requestCode)
        }
    }

    /**
     * 用户是不是明确拒绝过（勾了「不再询问」那种）。
     * 安卓 6.0 以前没有这个概念，一律当作「没拒绝过」。
     */
    fun shouldShowRationale(activity: Activity, permission: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            activity.shouldShowRequestPermissionRationale(permission)
        } else {
            false
        }

    /**
     * 读 `Settings.Global` 里的值。`Settings.Global.getString` 是 API 17 才有；
     * 安卓 4.1（16）上退回读旧的 `Settings.Secure`，读不到就返回 null。
     */
    fun globalString(context: Context, key: String): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            try {
                android.provider.Settings.Global.getString(context.contentResolver, key)
            } catch (e: Exception) {
                null
            }
        } else {
            try {
                android.provider.Settings.Secure.getString(context.contentResolver, key)
            } catch (e: Exception) {
                null
            }
        }

    /**
     * 屏幕真实像素尺寸（含状态栏、导航栏）。
     *
     * `Display.getRealSize` / `getRealMetrics` 是 API 17 才有。低版本上用 `getSize` / `getMetrics`：
     * 安卓 4.1 上这两者拿到的就是整屏（那时还没有后来那套窗口内边距的概念），结果一致。
     */
    fun realSize(display: Display): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            return point.x to point.y
        }
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /** 视图所在的 [Display]。`View.getDisplay` 是 API 17 才有，低版本走 `WindowManager`。 */
    @Suppress("DEPRECATION")
    fun displayOf(view: View): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            view.display
        } else {
            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            wm?.defaultDisplay
        }

    /** 视图是否已经挂到窗口上。`isAttachedToWindow` 是 API 19 才有。 */
    fun isAttached(view: View): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.isAttachedToWindow
        } else {
            // 低版本用「有没有窗口令牌」判断，和 isAttachedToWindow 语义一致。
            view.windowToken != null
        }

    /**
     * 蓝牙设备的类型（经典 / BLE / 双模）。`BluetoothDevice.getType` 是 API 18 才有。
     * 低版本上返回 [android.bluetooth.BluetoothDevice.DEVICE_TYPE_UNKNOWN]，调用方按「不知道」处理。
     */
    @android.annotation.SuppressLint("MissingPermission") // 调用方负责保证蓝牙权限（拿不到会走下面的 catch）
    fun bluetoothDeviceType(device: android.bluetooth.BluetoothDevice): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            @Suppress("DEPRECATION")
            device.type
        } else {
            android.bluetooth.BluetoothDevice.DEVICE_TYPE_UNKNOWN
        }
}
