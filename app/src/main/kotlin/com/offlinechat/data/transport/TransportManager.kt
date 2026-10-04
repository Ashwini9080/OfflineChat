package com.offlinechat.data.transport

import com.offlinechat.data.transport.bluetooth.BluetoothTransport
import com.offlinechat.data.transport.wifi.WifiDirectTransport
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransportManager @Inject constructor(
    private val bluetoothTransport: BluetoothTransport,
    private val wifiDirectTransport: WifiDirectTransport
) : MessageTransport {

    override val transportType: TransportType = TransportType.BLUETOOTH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incomingEnvelopes = MutableSharedFlow<MessageEnvelope>(extraBufferCapacity = 128)
    override val incomingEnvelopes: Flow<MessageEnvelope> = _incomingEnvelopes.asSharedFlow()

    init {
        scope.launch {
            merge(
                bluetoothTransport.incomingEnvelopes,
                wifiDirectTransport.incomingEnvelopes
            ).collect { envelope ->
                _incomingEnvelopes.emit(envelope)
            }
        }
    }

    override fun isConnected(peerId: String): Boolean {
        return bluetoothTransport.isConnected(peerId) || wifiDirectTransport.isConnected(peerId)
    }

    override fun observeConnectionState(peerId: String): Flow<PeerConnectionState> {
        return bluetoothTransport.observeConnectionState(peerId)
    }

    override suspend fun connect(peer: Peer): Result<Unit> {
        return when (peer.transportType) {
            TransportType.WIFI_DIRECT -> {
                val res = wifiDirectTransport.connect(peer)
                if (res.isFailure) bluetoothTransport.connect(peer) else res
            }
            TransportType.BLUETOOTH -> bluetoothTransport.connect(peer)
        }
    }

    override suspend fun sendEnvelope(envelope: MessageEnvelope): Result<Unit> {
        if (bluetoothTransport.isConnected(envelope.receiverId)) {
            return bluetoothTransport.sendEnvelope(envelope)
        }
        if (wifiDirectTransport.isConnected(envelope.receiverId)) {
            return wifiDirectTransport.sendEnvelope(envelope)
        }
        // Attempt bluetooth default
        return bluetoothTransport.sendEnvelope(envelope)
    }

    override suspend fun disconnect(peerId: String) {
        bluetoothTransport.disconnect(peerId)
        wifiDirectTransport.disconnect(peerId)
    }

    override suspend fun shutdown() {
        bluetoothTransport.shutdown()
        wifiDirectTransport.shutdown()
    }
}
