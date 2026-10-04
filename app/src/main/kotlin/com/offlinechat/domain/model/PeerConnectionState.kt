package com.offlinechat.domain.model

/**
 * Represents the discrete connection lifecycle state between the local device and a nearby peer.
 */
sealed interface PeerConnectionState {
    data object Disconnected : PeerConnectionState
    data object Connecting : PeerConnectionState
    data object Authenticating : PeerConnectionState
    data object Connected : PeerConnectionState
    data class Failed(val reason: String) : PeerConnectionState
}
