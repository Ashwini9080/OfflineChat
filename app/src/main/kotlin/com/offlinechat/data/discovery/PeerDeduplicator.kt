package com.offlinechat.data.discovery

import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles duplicate suppression, identity resolution, name refinement,
 * signal strength (RSSI) tracking, and sorting for discovered nearby peers.
 */
@Singleton
class PeerDeduplicator @Inject constructor() {

    /**
     * Merges a newly received advertisement/inquiry result into the existing peer list:
     * - Prevents duplicate entries when discovered across multiple inquiries or protocols (Classic + BLE)
     * - Keyed by unique device ID and Bluetooth MAC address, NEVER by display name
     * - Updates RSSI and timestamp
     * - Upgrades generic placeholder names ("Bluetooth Device (XX:XX)") to resolved friendly names
     * - Keeps the list stably sorted with highest signal strength (nearest devices) at the top
     */
    fun updatePeerList(
        currentList: List<Peer>,
        deviceId: String,
        displayName: String,
        bluetoothAddress: String?,
        rssi: Int,
        transportType: TransportType = TransportType.BLUETOOTH,
        timestamp: Long = System.currentTimeMillis()
    ): List<Peer> {
        val updated = currentList.toMutableList()
        val existingIndex = updated.indexOfFirst {
            it.deviceId == deviceId || (bluetoothAddress != null && it.bluetoothAddress.equals(bluetoothAddress, ignoreCase = true))
        }

        if (existingIndex >= 0) {
            val existing = updated[existingIndex]
            val resolvedName = when {
                // If existing has a generic placeholder and new name is friendly, upgrade it
                isGenericName(existing.displayName) && !isGenericName(displayName) -> displayName
                // If new name is non-empty and friendly, preserve or upgrade
                !isGenericName(displayName) && displayName.isNotBlank() -> displayName
                else -> existing.displayName
            }

            // If existing deviceId was a MAC fallback (12 hex chars without colon) and new ID is an app ID, upgrade it
            val isExistingMacFallback = existing.bluetoothAddress != null &&
                    existing.deviceId == existing.bluetoothAddress.replace(":", "").lowercase()
            val resolvedDeviceId = if (isExistingMacFallback && deviceId != existing.deviceId) {
                deviceId
            } else {
                existing.deviceId
            }

            updated[existingIndex] = existing.copy(
                deviceId = resolvedDeviceId,
                displayName = resolvedName,
                bluetoothAddress = bluetoothAddress ?: existing.bluetoothAddress,
                rssi = rssi,
                lastSeenAt = timestamp
            )
        } else {
            val newPeer = Peer(
                deviceId = deviceId,
                displayName = displayName,
                bluetoothAddress = bluetoothAddress,
                rssi = rssi,
                transportType = transportType,
                lastSeenAt = timestamp
            )
            updated.add(newPeer)
        }

        // Sort descending by RSSI so closest peers appear first
        updated.sortByDescending { it.rssi }
        return updated
    }

    private fun isGenericName(name: String): Boolean {
        return name.startsWith("Bluetooth Device", ignoreCase = true) ||
                name.startsWith("Peer-", ignoreCase = true) ||
                name.isBlank()
    }
}
