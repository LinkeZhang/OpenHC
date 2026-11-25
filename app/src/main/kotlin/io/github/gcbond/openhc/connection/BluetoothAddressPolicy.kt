package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.core.common.identity.BluetoothAddress

/** 车机蓝牙地址取自哪里。 */
enum class BluetoothSource {
    /** 车机系统属性。 */
    SYSTEM_PROPERTY,

    /** 用户在设置里手填。 */
    MANUAL,

    /** 都没有：按取不到处理（线上发 `02:00:00:00:00:00`，设备 ID 按退化规则算），有线照样能连。 */
    UNAVAILABLE,
}

/** 选定的车机蓝牙地址。[address] 是大写、冒号分隔的文本；取不到时为 null。 */
data class BluetoothChoice(val address: String?, val source: BluetoothSource)

/**
 * 车机蓝牙地址怎么定。纯逻辑。
 *
 * 安卓 6.0 起普通应用读不到本机蓝牙地址（系统只给 `02:00:00:00:00:00`）。有的车机系统把它放在一个普通应用可读的
 * 系统属性里（[SYSTEM_PROPERTY_KEYS]：部分车机写在 `persist.sys.bt_addr`，德赛西威一类车机写在
 * `persist.vehicle.bt.mac_addr`），按顺序取第一个有效的。用户在设置里手填了有效地址时优先用手填的
 * （车主可以借此覆盖系统的值，例如让本应用和车机上另一个投屏应用用不同的身份）；没填就用系统属性；都没有按取不到处理。
 *
 * 地址参与 carInfo、控制通道握手、认证身份和设备信息里的本机标识：**地址变了，手机会把车机当成另一台车**，
 * 之前的配对记录对不上，要重新输入连接码。
 */
object BluetoothAddressPolicy {
    /** 最常用的那个属性名，保留给文档和旧代码引用。 */
    const val SYSTEM_PROPERTY_KEY = "persist.sys.bt_addr"

    /** 存车机蓝牙地址的系统属性，按顺序读，取第一个有效的。 */
    val SYSTEM_PROPERTY_KEYS = listOf(SYSTEM_PROPERTY_KEY, "persist.vehicle.bt.mac_addr")

    /** 几个系统属性读到的值（按 [SYSTEM_PROPERTY_KEYS] 的顺序）里第一个有效的地址；都无效返回 null。 */
    fun firstValid(values: List<String?>): String? = values.firstNotNullOfOrNull { normalize(it) }

    /**
     * 车机可能用来存本机蓝牙地址的系统属性名，按可靠性从高到低排。
     * 各家方案商写法不同（高通、联发科、瑞芯微、比亚迪、长城等都有各自的），全部试一遍。
     */
    val PROPERTY_KEYS = listOf(
        "persist.sys.bt_addr",
        "persist.vendor.bt.bdaddr",
        "persist.bluetooth.bdaddr",
        "ro.bt.bdaddr_path_addr",
        "persist.service.bdroid.bdaddr",
        "ro.boot.btmacaddr",
        "net.hostname.bt",
        "persist.sys.bt.mac",
        "ro.bt.bdaddr",
        "bluetooth.address",
        "persist.bt.mac",
        "ro.bt.mac",
        "ro.bluetooth.address",
        "ro.vendor.bt.bdaddr",
        "persist.radio.btmacaddr",
        "persist.vendor.qcom.bluetooth.bdaddr",
        "bt_mac",
        "ro.product.bt.address",
    )

    /** Settings.Global / Settings.Secure 里可能存着本机蓝牙地址的键。 */
    val SETTINGS_KEYS = listOf(
        "bluetooth_address",
        "bluetooth_addr",
        "bt_address",
        "bt_mac_address",
    )

    /** 全 0、全 F：属性没初始化或读错了。 */
    private val INVALID = setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", BluetoothAddress.UNAVAILABLE)

    fun choose(systemProperty: String?, manual: String?): BluetoothChoice {
        normalize(manual)?.let { return BluetoothChoice(it, BluetoothSource.MANUAL) }
        normalize(systemProperty)?.let { return BluetoothChoice(it, BluetoothSource.SYSTEM_PROPERTY) }
        return BluetoothChoice(null, BluetoothSource.UNAVAILABLE)
    }

    /**
     * 无线连接用的地址：**手填的优先**，没填才用系统里读到的。
     *
     * 为什么手填优先：从系统读地址本来就没有可靠办法（安卓 6 起公开接口一律给占位值），只能碰运气去猜厂商写在哪个
     * 属性里。猜错了会读到一个**看起来合法但其实是别的设备**的地址，二维码里就带着错地址，手机拿去连车机蓝牙永远
     * 连不上，车机这边只停在「等扫码」、不报任何错——这比读不到更难查。
     *
     * 所以让车主手填的地址压过系统读到的值：车主照着「关于本机」填的那一份一定是真的。系统读到的值只在没填时兜底。
     */
    fun chooseForWireless(systemProperty: String?, manual: String?): BluetoothChoice {
        normalize(manual)?.let { return BluetoothChoice(it, BluetoothSource.MANUAL) }
        normalize(systemProperty)?.let { return BluetoothChoice(it, BluetoothSource.SYSTEM_PROPERTY) }
        return BluetoothChoice(null, BluetoothSource.UNAVAILABLE)
    }

    /** 第一个字节的「本地管理」位为 1：这类地址是软件编出来的，不会是蓝牙芯片的出厂地址。 */
    fun isLocallyAdministered(address: String): Boolean =
        BluetoothAddress.parse(address)[0].toInt() and 0x02 != 0

    /**
     * 整理成 `AA:BB:CC:DD:EE:FF`：接受冒号或 `-` 分隔、大小写，也接受不带分隔的 12 位十六进制；
     * 前后空白去掉。格式不对，或是全 0、全 F、`02:00:00:00:00:00` 这类占位值，返回 null。
     */
    fun normalize(text: String?): String? {
        val trimmed = text?.trim() ?: return null
        val withSeparators = if (trimmed.length == 12 && trimmed.all { it.isAsciiHex() }) {
            trimmed.chunked(2).joinToString(":")
        } else {
            trimmed
        }
        val bytes = try {
            BluetoothAddress.parse(withSeparators)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val formatted = BluetoothAddress.format(bytes)
        return formatted.takeIf { it !in INVALID }
    }

    private fun Char.isAsciiHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
