package com.offlinechat.domain.model

data class Peer(
    val deviceId: String,
    val displayName: String,
    val bluetoothAddress: String? = null,
    val publicKeyBytes: ByteArray = ByteArray(0),
    val rssi: Int = -100,
    val isTrusted: Boolean = false,
    val isConnected: Boolean = false,
    val transportType: TransportType = TransportType.BLUETOOTH,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val trustState: PeerTrustState = PeerTrustState.UNKNOWN,
    val safetyNumber: String? = null,
    val identityFingerprint: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Peer) return false
        return deviceId == other.deviceId &&
                transportType == other.transportType &&
                isTrusted == other.isTrusted &&
                isConnected == other.isConnected &&
                trustState == other.trustState &&
                safetyNumber == other.safetyNumber &&
                identityFingerprint == other.identityFingerprint &&
                publicKeyBytes.contentEquals(other.publicKeyBytes)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + transportType.hashCode()
        result = 31 * result + isTrusted.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + trustState.hashCode()
        result = 31 * result + (safetyNumber?.hashCode() ?: 0)
        result = 31 * result + (identityFingerprint?.hashCode() ?: 0)
        result = 31 * result + publicKeyBytes.contentHashCode()
        return result
    }
}
