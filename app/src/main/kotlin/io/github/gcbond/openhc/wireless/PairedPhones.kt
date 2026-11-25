package io.github.gcbond.openhc.wireless

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import io.github.gcbond.openhc.platform.bluetooth.BluetoothStatus

/** 车机蓝牙里已经配对过的一台手机。 */
internal data class PairedPhone(val address: String, val name: String)

/**
 * 车机蓝牙里已经配对的手机清单：设置页「让车机连这台手机」列出来让车主点。
 *
 * 为什么按「已配对」列：车机主动去连手机走的是手机的蓝牙控制服务（RFCOMM 客户端方向），手机那边一般要求先配对。
 * 车主在车机的蓝牙设置里配过一次，这里就能列出来；没配对过也能在同一个对话框里手填地址。
 */
internal object PairedPhones {

    /** 读已配对设备要「附近的设备」权限（安卓 12 起）；没有权限时返回空清单。 */
    @SuppressLint("MissingPermission")
    fun list(context: Context): List<PairedPhone> = try {
        val devices: Set<BluetoothDevice> = BluetoothStatus.adapter(context)?.bondedDevices ?: emptySet()
        devices.mapNotNull { device ->
            val address = try {
                device.address
            } catch (e: SecurityException) {
                null
            } ?: return@mapNotNull null
            val name = try {
                device.name
            } catch (e: SecurityException) {
                null
            }
            PairedPhone(address.uppercase(), name?.takeIf { it.isNotBlank() } ?: address.uppercase())
        }.sortedBy { it.name.lowercase() }
    } catch (e: SecurityException) {
        emptyList()
    } catch (e: RuntimeException) {
        emptyList()
    }
}
