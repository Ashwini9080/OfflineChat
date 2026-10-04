package com.offlinechat.data.transport

import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import kotlinx.coroutines.flow.Flow

interface MessageTransport {
    val transportType: TransportType

    /** Stream of incoming envelopes received over this transport */
    val incomingEnvelopes: Flow<MessageEnvelope>

    /** Synchronous check whether an active connection to peer exists */
    fun isConnected(peerId: String): Boolean

    /** Observable stream of connection state transitions for a given peer */
    fun observeConnectionState(peerId: String): Flow<PeerConnectionState>

    /** Opens a P2P data connection with the peer */
    suspend fun connect(peer: Peer): Result<Unit>

    /** Sends a framed envelope to the connected peer */
    suspend fun sendEnvelope(envelope: MessageEnvelope): Result<Unit>

    /** Closes channel with peer */
    suspend fun disconnect(peerId: String)

    /** Closes all active connections and releases resources */
    suspend fun shutdown()
}
