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
    val lastSeenAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Peer) return false
        return deviceId == other.deviceId &&
                transportType == other.transportType &&
                isTrusted == other.isTrusted &&
                isConnected == other.isConnected &&
                publicKeyBytes.contentEquals(other.publicKeyBytes)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + transportType.hashCode()
        result = 31 * result + isTrusted.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + publicKeyBytes.contentHashCode()
        return result
    }
}
