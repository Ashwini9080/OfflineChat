package com.offlinechat.data.transport.bluetooth

import android.content.Context
import com.offlinechat.data.connection.BluetoothConnectionManager
import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import com.offlinechat.domain.repository.PreferencesRepository
import com.offlinechat.security.IdentityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MessageTransport] implementation for Bluetooth Classic RFCOMM.
 *
 * Connects the messaging and synchronization domain to [BluetoothConnectionManager]
 * for bidirectional frame transmission and stream observation.
 */
@Singleton
class BluetoothTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager,
    private val preferencesRepository: PreferencesRepository,
    private val connectionManager: BluetoothConnectionManager
) : MessageTransport {

    override val transportType: TransportType = TransportType.BLUETOOTH

    override val incomingEnvelopes: Flow<MessageEnvelope> = connectionManager.incomingEnvelopes

    override fun isConnected(peerId: String): Boolean {
        return connectionManager.isConnected(peerId)
    }

    override fun observeConnectionState(peerId: String): Flow<PeerConnectionState> {
        return connectionManager.observeConnectionState(peerId)
    }

    override suspend fun connect(peer: Peer): Result<Unit> {
        return connectionManager.connect(peer)
    }

    override suspend fun sendEnvelope(envelope: MessageEnvelope): Result<Unit> {
        return connectionManager.sendEnvelope(envelope.receiverId, envelope)
    }

    override suspend fun disconnect(peerId: String) {
        connectionManager.disconnect(peerId)
    }

    override suspend fun shutdown() {
        connectionManager.disconnectAll()
    }
}
