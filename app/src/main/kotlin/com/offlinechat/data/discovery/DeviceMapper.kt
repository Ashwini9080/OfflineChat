package com.offlinechat.data.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts platform Android Bluetooth primitives (BluetoothDevice, ScanResult)
 * into domain [Peer] entities without leaking Android framework objects into the domain or UI layers.
 */
@Singleton
class DeviceMapper @Inject constructor() {

    @SuppressLint("MissingPermission")
    fun mapClassicDevice(
        device: BluetoothDevice,
        extraName: String?,
        rssi: Int,
        timestamp: Long = System.currentTimeMillis()
    ): Peer {
        val safeName = runCatching { device.name }.getOrNull()
        val address = runCatching { device.address }.getOrNull().orEmpty()
        val displayName = when {
            !extraName.isNullOrBlank() -> extraName
            !safeName.isNullOrBlank() -> safeName
            address.isNotBlank() -> "Bluetooth Device (${address.takeLast(5)})"
            else -> "Nearby Device"
        }

        val deviceId = if (address.isNotBlank()) {
            address.replace(":", "").lowercase()
        } else {
            "bt_${abs(displayName.hashCode())}"
        }

        val resolvedRssi = if (rssi != Short.MIN_VALUE.toInt() && rssi != 0) rssi else -80

        return Peer(
            deviceId = deviceId,
            displayName = displayName,
            bluetoothAddress = address.ifBlank { null },
            rssi = resolvedRssi,
            transportType = TransportType.BLUETOOTH,
            lastSeenAt = timestamp
        )
    }

    @SuppressLint("MissingPermission")
    fun mapBleScanResult(
        result: ScanResult,
        serviceUuid: UUID,
        timestamp: Long = System.currentTimeMillis()
    ): Peer? {
        val device = result.device ?: return null
        val address = runCatching { device.address }.getOrNull().orEmpty()
        val scanRecord = result.scanRecord

        val serviceData = scanRecord?.getServiceData(ParcelUuid(serviceUuid))
        val deviceId = serviceData?.let { bytes ->
            String(bytes, StandardCharsets.UTF_8).trim()
        }?.takeIf { it.isNotBlank() } ?: address.replace(":", "").lowercase()

        val scanName = scanRecord?.deviceName
        val safeDeviceName = runCatching { device.name }.getOrNull()
        val displayName = when {
            !scanName.isNullOrBlank() -> scanName
            !safeDeviceName.isNullOrBlank() -> safeDeviceName
            else -> "Peer-${deviceId.take(6)}"
        }

        return Peer(
            deviceId = deviceId,
            displayName = displayName,
            bluetoothAddress = address.ifBlank { null },
            rssi = result.rssi,
            transportType = TransportType.BLUETOOTH,
            lastSeenAt = timestamp
        )
    }

    private fun abs(value: Int): Int = if (value < 0) -value else value
}
