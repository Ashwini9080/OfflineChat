package com.offlinechat.domain.connection

import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import kotlinx.coroutines.flow.Flow

/**
 * Manager interface responsible for initiating connections, enforcing connection ownership,
 * tracking active peer connections, monitoring connection states, and releasing connection resources.
 */
interface ConnectionManager {

    /**
     * Observes the discrete connection lifecycle state for a given peer.
     */
    fun observeConnectionState(peerId: String): Flow<PeerConnectionState>

    /**
     * Gets the instantaneous connection state for a given peer.
     */
    fun getConnectionState(peerId: String): PeerConnectionState

    /**
     * Checks if a peer is actively connected.
     */
    fun isConnected(peerId: String): Boolean

    /**
     * Retrieves the active [PeerConnection] handle for a given peer, if one exists.
     */
    fun getActiveConnection(peerId: String): PeerConnection?

    /**
     * Initiates a connection to the specified peer.
     * Prevents duplicate/conflicting connection attempts.
     */
    suspend fun connect(peer: Peer): Result<Unit>

    /**
     * Cancels an in-flight connection attempt for a given peer.
     */
    suspend fun cancelConnection(peerId: String)

    /**
     * Safely closes an active connection with a peer.
     */
    suspend fun disconnect(peerId: String)

    /**
     * Closes all active connections and cleans up background resources.
     */
    suspend fun disconnectAll()
}
