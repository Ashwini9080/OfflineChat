package com.offlinechat.data.transport.wifi

import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiDirectTransport @Inject constructor() : MessageTransport {

    override val transportType: TransportType = TransportType.WIFI_DIRECT

    private val _incomingEnvelopes = MutableSharedFlow<MessageEnvelope>(extraBufferCapacity = 64)
    override val incomingEnvelopes: Flow<MessageEnvelope> = _incomingEnvelopes.asSharedFlow()

    override fun isConnected(peerId: String): Boolean = false

    override fun observeConnectionState(peerId: String): Flow<PeerConnectionState> =
        flowOf(PeerConnectionState.Disconnected)

    override suspend fun connect(peer: Peer): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Wi-Fi Direct transport reserved for future phase"))
    }

    override suspend fun sendEnvelope(envelope: MessageEnvelope): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Wi-Fi Direct transport not active"))
    }

    override suspend fun disconnect(peerId: String) {}

    override suspend fun shutdown() {}
}
