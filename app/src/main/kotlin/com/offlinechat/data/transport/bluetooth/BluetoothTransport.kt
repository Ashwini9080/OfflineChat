package com.offlinechat.data.transport.bluetooth

import android.content.Context
import android.util.Log
import com.offlinechat.data.connection.BluetoothConnectionManager
import com.offlinechat.data.connection.BluetoothPeerConnection
import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import com.offlinechat.domain.repository.PreferencesRepository
import com.offlinechat.security.IdentityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.DataOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MessageTransport] implementation for Bluetooth Classic RFCOMM.
 *
 * Coordinates with [BluetoothConnectionManager] for socket connectivity and lifecycle,
 * and handles message envelope framing and serialization over active sockets.
 */
@Singleton
class BluetoothTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager,
    private val preferencesRepository: PreferencesRepository,
    private val connectionManager: BluetoothConnectionManager
) : MessageTransport {

    companion object {
        private const val TAG = "BluetoothTransport"
    }

    override val transportType: TransportType = TransportType.BLUETOOTH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _incomingEnvelopes = MutableSharedFlow<MessageEnvelope>(extraBufferCapacity = 128)
    override val incomingEnvelopes: Flow<MessageEnvelope> = _incomingEnvelopes.asSharedFlow()

    override fun isConnected(peerId: String): Boolean {
        return connectionManager.isConnected(peerId)
    }

    override fun observeConnectionState(peerId: String): Flow<PeerConnectionState> {
        return connectionManager.observeConnectionState(peerId)
    }

    override suspend fun connect(peer: Peer): Result<Unit> {
        return connectionManager.connect(peer)
    }

    override suspend fun sendEnvelope(envelope: MessageEnvelope): Result<Unit> = withContext(Dispatchers.IO) {
        val activeConn = connectionManager.getActiveConnection(envelope.receiverId) as? BluetoothPeerConnection
        val socket = activeConn?.socket
            ?: return@withContext Result.failure(IllegalStateException("No active RFCOMM connection to peer ${envelope.receiverId}"))

        if (!socket.isConnected) {
            return@withContext Result.failure(IllegalStateException("Socket is disconnected"))
        }

        try {
            writeEnvelopeToStream(socket.outputStream, envelope)
            Log.d(TAG, "Sent envelope [${envelope.messageType}:${envelope.messageId}] to ${envelope.receiverId}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error transmitting envelope to ${envelope.receiverId}", e)
            Result.failure(e)
        }
    }

    private fun writeEnvelopeToStream(outputStream: java.io.OutputStream, envelope: MessageEnvelope) {
        val jsonString = json.encodeToString(MessageEnvelope.serializer(), envelope)
        val bytes = jsonString.toByteArray(Charsets.UTF_8)
        val dataOut = DataOutputStream(outputStream)

        synchronized(outputStream) {
            // Frame format: 4-byte big-endian length + JSON payload
            dataOut.writeInt(bytes.size)
            dataOut.write(bytes)
            dataOut.flush()
        }
    }

    override suspend fun disconnect(peerId: String) {
        connectionManager.disconnect(peerId)
    }

    override suspend fun shutdown() {
        connectionManager.disconnectAll()
    }
}
