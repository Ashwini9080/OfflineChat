package com.offlinechat.domain.model

/**
 * Represents the discrete connection lifecycle state between the local device and a nearby peer.
 */
sealed interface PeerConnectionState {
    data object Idle : PeerConnectionState
    data object Connecting : PeerConnectionState
    data object Connected : PeerConnectionState
    data object Disconnecting : PeerConnectionState
    data object Disconnected : PeerConnectionState

    data class ConnectionFailed(val reason: String = "") : PeerConnectionState
    data class ConnectionRejected(val reason: String = "") : PeerConnectionState
    data class ConnectionTimeout(val timeoutMs: Long = 15000L) : PeerConnectionState
    data class ConnectionLost(val reason: String = "") : PeerConnectionState
    data object BluetoothDisabled : PeerConnectionState
    data object PermissionRevoked : PeerConnectionState

    // Backward compatibility aliases / states
    data object Authenticating : PeerConnectionState
    data class Failed(val reason: String) : PeerConnectionState
}
