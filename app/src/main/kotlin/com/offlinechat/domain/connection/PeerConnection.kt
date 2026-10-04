package com.offlinechat.domain.connection

import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction representing a 1-to-1 peer connection over a hardware transport (e.g. Bluetooth RFCOMM).
 *
 * Exposes observable connection lifecycle states and lifecycle operations without exposing
 * Android Bluetooth platform classes to domain and presentation layers.
 */
interface PeerConnection {
    /** Unique device identifier of the remote peer */
    val peerId: String

    /** Observable flow of connection lifecycle states */
    val connectionState: StateFlow<PeerConnectionState>

    /** Returns true if the underlying socket is actively connected */
    val isConnected: Boolean

    /**
     * Attempts to establish a connection to the specified peer.
     * Completes when connected or returns failure on timeout/error.
     */
    suspend fun connect(peer: Peer): Result<Unit>

    /**
     * Safely closes the peer connection and transitions state to Disconnected.
     */
    suspend fun disconnect()

    /**
     * Releases any listeners, receivers, or background coroutine jobs.
     */
    fun release()
}
