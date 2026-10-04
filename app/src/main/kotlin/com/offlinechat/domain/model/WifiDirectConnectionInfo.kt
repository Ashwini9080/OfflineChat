package com.offlinechat.domain.model

/**
 * Clean domain representation of established Wi-Fi Direct P2P group and connection metadata.
 * Free from Android framework dependencies (e.g. WifiP2pInfo, WifiP2pGroup, InetAddress).
 */
data class WifiDirectConnectionInfo(
    val groupFormed: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    val networkName: String? = null,
    val passphrase: String? = null,
    val clientCount: Int = 0
)
