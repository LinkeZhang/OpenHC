package io.github.gcbond.openhc.connection

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import io.github.gcbond.openhc.platform.media.DisplayRefreshRate

/**
 * 一个蓝牙地址候选值，以及它是从哪儿读到的（只用于设置页诊断展示）。
 *
 * [usable] 为 false 表示读到了、但值不能直接用于二维码（占位值或格式不对），界面上会标出来。
 */
internal data class BluetoothAddressCandidate(
    val address: String,
    val source: String,
    val usable: Boolean = true,
)

/** 从系统读车机的蓝牙地址和屏幕参数。 */
internal object DeviceInfo {

    /**
     * 依次尝试各种能读到本机蓝牙地址的地方，返回第一个像真实地址的值；都读不到返回 null。
     *
     * 安卓 6.0 起普通应用拿不到本机蓝牙地址（公开接口一律给 `02:00:00:00:00:00`），但车机厂商通常会在系统属性
     * 或系统设置里留一份，所以这里按「最可能是真地址」的顺序逐个试：
     * 1. 一批常见的蓝牙地址系统属性（不同方案商写法不同，[BluetoothAddressPolicy.PROPERTY_KEYS] 里都列了）；
     * 2. `Settings.Global` / `Settings.Secure` 里几个常见的键（部分车机系统把地址写在这里，普通应用可读）；
     * 3. 反射 `BluetoothAdapter.getAddress()`（安卓 6 之前是真地址，之后多半是占位值，试一下不吃亏）。
     *
     * 每个候选值都过一遍 [BluetoothAddressPolicy.normalize]，占位值（全 0、全 F、`02:00:00:00:00:00`）和格式不对的
     * 直接跳过，所以这里返回的一定是能直接用的地址。全都读不到时返回 null，界面上让车主手填。
     */
    fun systemBluetoothAddress(context: Context? = null): String? {
        // 反射蓝牙服务内部接口拿到的地址是最可靠的（实测某车机只有这条路读到的是「关于本机」里的真地址），
        // 所以放在最前面；读不到再退回属性、Settings、公开接口。
        usable(internalServiceAddress())?.let { return it }
        for (key in BluetoothAddressPolicy.PROPERTY_KEYS) {
            usable(systemProperty(key))?.let { return it }
        }
        if (context != null) {
            for (key in BluetoothAddressPolicy.SETTINGS_KEYS) {
                usable(settingsValue(context, key))?.let { return it }
            }
        }
        usable(adapterAddress())?.let { return it }
        return null
    }

    /**
     * 反射 `BluetoothAdapter.mService`（`IBluetooth` 接口）直接调它的无参 `getAddress()`。
     *
     * 公开的 `BluetoothAdapter.getAddress()` 走的是另一条路，安卓 6 起会给普通应用换成占位值；而蓝牙服务本身
     * 在不少车机 ROM 上没有这个限制，返回的是真实地址。字段名 `mService` 从安卓 4.2 一直到 13 都没改过。
     *
     * 这是隐藏接口，只在读不到时静默失败：调用方拿不到就继续试别的来源。
     */
    @SuppressLint("PrivateApi", "HardwareIds")
    private fun internalServiceAddress(): String? = try {
        val adapter = Class.forName("android.bluetooth.BluetoothAdapter")
            .getMethod("getDefaultAdapter")
            .invoke(null) ?: return null
        val serviceField = adapter.javaClass.getDeclaredField("mService").apply { isAccessible = true }
        val service = serviceField.get(adapter) ?: return null
        val getAddress = Class.forName("android.bluetooth.IBluetooth").getMethod("getAddress")
        (getAddress.invoke(service) as? String)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    } catch (e: RuntimeException) {
        null
    } catch (e: LinkageError) {
        null
    }

    private fun usable(raw: String?): String? = BluetoothAddressPolicy.normalize(raw)

    /**
     * 把所有能读到的蓝牙地址候选都列出来（含来源名），给设置页「自动读取」做诊断用。
     *
     * [systemBluetoothAddress] 只返回第一个能用的值；一旦那个值其实是错的（例如某个厂商属性里存的是别的设备的地址），
     * 车主没法知道错在哪、也没法判断该手填哪个。这里把每个来源的值都摊开，车主可以对着「关于本机」挑正确的那个。
     *
     * 同一个地址可能被好几个属性重复记录，这里按地址去重，来源名用「、」连起来，列表才不会太长。
     * 占位值（全 0、全 F、`02:00:00:00:00:00`）和格式不对的值也会列出来，但标记为不可用——车主能看出
     * 「这个属性确实存在、只是里面是占位值」，这对判断车机把真地址放在哪很有用。
     */
    fun bluetoothAddressCandidates(context: Context? = null): List<BluetoothAddressCandidate> {
        val raw = ArrayList<Pair<String, String>>()
        // 与 systemBluetoothAddress 的顺序一致：最可靠的排在最前面。
        internalServiceAddress()?.let { raw += it to "蓝牙服务内部接口" }
        for (key in BluetoothAddressPolicy.PROPERTY_KEYS) {
            systemProperty(key)?.let { raw += it to "属性 $key" }
        }
        if (context != null) {
            for (key in BluetoothAddressPolicy.SETTINGS_KEYS) {
                settingsValue(context, key)?.let { raw += it to "设置 $key" }
            }
        }
        adapterAddress()?.let { raw += it to "蓝牙适配器接口" }

        // 按「整理后的地址」分组：能整理成标准写法的归到一起，整理不了的按原值分组。
        val grouped = LinkedHashMap<String, MutableList<String>>()
        val normalizedOf = HashMap<String, String?>()
        for ((value, source) in raw) {
            val normalized = BluetoothAddressPolicy.normalize(value)
            val key = normalized ?: value.trim()
            normalizedOf[key] = normalized
            grouped.getOrPut(key) { ArrayList() } += source
        }
        return grouped.map { (key, sources) ->
            val normalized = normalizedOf[key]
            BluetoothAddressCandidate(
                address = normalized ?: key,
                source = sources.joinToString("、"),
                usable = normalized != null,
            )
        }
    }

    /** 读一个系统属性，读不到或没有这个属性返回 null。 */
    @SuppressLint("PrivateApi")
    private fun systemProperty(key: String): String? = try {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** 从 Settings.Global 和 Settings.Secure 里找一个键；两边都没有返回 null。 */
    @SuppressLint("HardwareIds")
    private fun settingsValue(context: Context, key: String): String? {
        // Settings.Global 是 API 17 才有；安卓 4.1 上 Compat 会退回 Settings.Secure。
        val global = io.github.gcbond.openhc.compat.Compat.globalString(context, key)
        if (!global.isNullOrBlank()) return global
        return try {
            android.provider.Settings.Secure.getString(context.contentResolver, key)
        } catch (e: RuntimeException) {
            null
        }
    }

    /** 反射 `BluetoothAdapter.getAddress()`：安卓 6 起对普通应用返回占位值，会被 [usable] 过滤掉。 */
    @SuppressLint("PrivateApi", "HardwareIds")
    private fun adapterAddress(): String? = try {
        val adapter = Class.forName("android.bluetooth.BluetoothAdapter")
            .getMethod("getDefaultAdapter")
            .invoke(null) ?: return null
        (adapter.javaClass.getMethod("getAddress").invoke(adapter) as? String)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** 默认屏幕（车机主屏）的实际像素、DPI 和按刷新率算出的帧率。 */
    fun screen(context: Context): ScreenSpec {
        // DisplayManager 是 API 17 才有；安卓 4.1 上退回 WindowManager.getDefaultDisplay。
        val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
        }
        val metrics = DisplayMetrics()
        val size = Point()
        if (display != null) {
            // getRealSize/getRealMetrics 是 API 17 才有，Compat 里按版本选实现。
            val (w, h) = io.github.gcbond.openhc.compat.Compat.realSize(display)
            size.set(w, h)
            @Suppress("DEPRECATION")
            display.getMetrics(metrics)
        }
        if (size.x <= 0 || size.y <= 0) {
            val fallback = context.resources.displayMetrics
            size.set(fallback.widthPixels, fallback.heightPixels)
            metrics.densityDpi = fallback.densityDpi
        }
        val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT
        return ScreenSpec(size.x, size.y, dpi, DisplayRefreshRate.declaredFps(context))
    }
}
