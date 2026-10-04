package com.offlinechat.transport.api

import com.offlinechat.core.model.TransportType

/**
 * A device discovered by a [Transport] scan.
 *
 * Created by [BleScanner] when it parses a valid BLE advertisement containing a
 * [security.model.DeviceCertificate]. Only devices with verified certificates
 * are surfaced as [PeerDevice]s — invalid advertisements are silently dropped.
 *
 * @param deviceId         Stable [DeviceIdentity.id] of the remote device.
 * @param displayName      Human-readable device name from the certificate.
 * @param bluetoothAddress MAC address used to open an RFCOMM socket.
 *                         Null for non-Bluetooth transports.
 * @param publicSigningKeyBytes  Raw EC P-256 public key bytes. Used to verify
 *                               subsequent message signatures from this peer.
 * @param publicDhKeyBytes   Ephemeral EC P-256 DH public key bytes for ECDH session setup.
 * @param rssi             Signal strength (negative dBm). Used by the UI to show
 *                         proximity indicators. Not used for routing decisions.
 * @param transport        Which transport discovered this peer.
 * @param discoveredAt     Unix epoch ms when first seen. Used to expire stale entries.
 */
data class PeerDevice(
    val deviceId: String,
    val displayName: String,
    val bluetoothAddress: String?,
    val publicSigningKeyBytes: ByteArray,
    val publicDhKeyBytes: ByteArray,
    val rssi: Int,
    val transport: TransportType,
    val discoveredAt: Long,
) {
    // Custom equals/hashCode because ByteArray uses referential equality by default.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerDevice) return false
        return deviceId == other.deviceId &&
            transport == other.transport &&
            publicSigningKeyBytes.contentEquals(other.publicSigningKeyBytes) &&
            publicDhKeyBytes.contentEquals(other.publicDhKeyBytes)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + transport.hashCode()
        result = 31 * result + publicSigningKeyBytes.contentHashCode()
        result = 31 * result + publicDhKeyBytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "PeerDevice(deviceId=$deviceId, displayName=$displayName, " +
            "transport=$transport, rssi=$rssi)"
}
