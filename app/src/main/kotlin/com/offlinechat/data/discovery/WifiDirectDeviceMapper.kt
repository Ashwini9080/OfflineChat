package com.offlinechat.data.discovery

import android.net.wifi.p2p.WifiP2pDevice
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps Android framework [WifiP2pDevice] objects into clean [Peer] domain models.
 *
 * ## Identity contract
 * The [WifiP2pDevice.deviceAddress] is used as a **temporary routing handle** to initiate a
 * Wi-Fi Direct group connection. It is NOT the cryptographic peer identity — that comes from
 * the Phase 7 certificate/key exchange which happens after physical connection is established.
 *
 * Do NOT promote the Wi-Fi MAC address to a long-term cryptographic identity.
 */
@Singleton
class WifiDirectDeviceMapper @Inject constructor() {

    /**
     * Maps a [WifiP2pDevice] discovered via [WifiP2pManager.requestPeers] into a [Peer].
     *
     * @param device The Android Wi-Fi P2P device from the framework.
     * @param timestamp Discovery timestamp in epoch-millis (defaults to now).
     * @return A domain [Peer] with [TransportType.WIFI_DIRECT] and no cryptographic key material
     *         (keys are populated later during the secure handshake).
     */
    fun mapDevice(
        device: WifiP2pDevice,
        timestamp: Long = System.currentTimeMillis(),
    ): Peer {
        val rawAddress = device.deviceAddress?.trim() ?: ""

        // Use MAC address as temporary routing key — NOT cryptographic identity
        val deviceId = if (rawAddress.isNotBlank()) {
            rawAddress.replace(":", "").lowercase()
        } else {
            // Fallback: hash of device name (still temporary, non-cryptographic)
            "wifid_${device.deviceName?.hashCode()?.let { Math.abs(it) } ?: 0}"
        }

        val displayName = when {
            !device.deviceName.isNullOrBlank() -> device.deviceName!!
            rawAddress.isNotBlank() -> "Wi-Fi Peer (${rawAddress.takeLast(5)})"
            else -> "Wi-Fi Direct Device"
        }

        return Peer(
            deviceId = deviceId,
            displayName = displayName,
            bluetoothAddress = null,          // Not known from Wi-Fi discovery
            publicKeyBytes = ByteArray(0),    // Populated after secure handshake (Phase 9+)
            rssi = -80,                       // WifiP2pDevice does not expose RSSI
            isTrusted = false,
            isConnected = false,
            transportType = TransportType.WIFI_DIRECT,
            lastSeenAt = timestamp,
        )
    }

    /**
     * Maps a list of [WifiP2pDevice] objects, deduplicating by [Peer.deviceId].
     *
     * Devices with an empty or null address are skipped to avoid unstable IDs.
     */
    fun mapDeviceList(
        devices: Collection<WifiP2pDevice>,
        timestamp: Long = System.currentTimeMillis(),
    ): List<Peer> {
        return devices
            .filter { it.deviceAddress?.isNotBlank() == true }
            .map { mapDevice(it, timestamp) }
            .distinctBy { it.deviceId }
    }
}
