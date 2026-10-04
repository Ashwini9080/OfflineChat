package com.offlinechat.data.connection

import com.offlinechat.domain.connection.ConnectionManager
import com.offlinechat.domain.connection.PeerConnection
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified [ConnectionManager] implementation that routes connection requests
 * dynamically based on [Peer.transportType] (Bluetooth Classic RFCOMM vs Wi-Fi Direct P2P).
 *
 * Preserves complete Bluetooth functionality without regressions while providing
 * first-class support for native Wi-Fi Direct peer connections.
 */
@Singleton
class UnifiedConnectionManager @Inject constructor(
    private val bluetoothConnectionManager: BluetoothConnectionManager,
    private val wifiDirectConnectionManager: WifiDirectConnectionManager
) : ConnectionManager {

    override fun observeConnectionState(peerId: String): Flow<PeerConnectionState> {
        return combine(
            bluetoothConnectionManager.observeConnectionState(peerId),
            wifiDirectConnectionManager.observeConnectionState(peerId)
        ) { btState, wifiState ->
            when {
                wifiState is PeerConnectionState.Connected -> wifiState
                btState is PeerConnectionState.Connected -> btState
                wifiState is PeerConnectionState.Connecting || wifiState is PeerConnectionState.Authenticating -> wifiState
                btState is PeerConnectionState.Connecting || btState is PeerConnectionState.Authenticating -> btState
                wifiState !is PeerConnectionState.Idle && wifiState !is PeerConnectionState.Disconnected -> wifiState
                else -> btState
            }
        }
    }

    override fun getConnectionState(peerId: String): PeerConnectionState {
        val wifiState = wifiDirectConnectionManager.getConnectionState(peerId)
        if (wifiState is PeerConnectionState.Connected || wifiState is PeerConnectionState.Connecting) {
            return wifiState
        }
        val btState = bluetoothConnectionManager.getConnectionState(peerId)
        if (btState is PeerConnectionState.Connected || btState is PeerConnectionState.Connecting) {
            return btState
        }
        return if (wifiState !is PeerConnectionState.Idle && wifiState !is PeerConnectionState.Disconnected) {
            wifiState
        } else {
            btState
        }
    }

    override fun isConnected(peerId: String): Boolean {
        return bluetoothConnectionManager.isConnected(peerId) ||
                wifiDirectConnectionManager.isConnected(peerId)
    }

    override fun getActiveConnection(peerId: String): PeerConnection? {
        return wifiDirectConnectionManager.getActiveConnection(peerId)
            ?: bluetoothConnectionManager.getActiveConnection(peerId)
    }

    override suspend fun connect(peer: Peer): Result<Unit> {
        return when (peer.transportType) {
            TransportType.WIFI_DIRECT -> {
                wifiDirectConnectionManager.connect(peer)
            }
            TransportType.BLUETOOTH -> {
                bluetoothConnectionManager.connect(peer)
            }
        }
    }

    override suspend fun cancelConnection(peerId: String) {
        wifiDirectConnectionManager.cancelConnection(peerId)
        bluetoothConnectionManager.cancelConnection(peerId)
    }

    override suspend fun disconnect(peerId: String) {
        wifiDirectConnectionManager.disconnect(peerId)
        bluetoothConnectionManager.disconnect(peerId)
    }

    override suspend fun disconnectAll() {
        wifiDirectConnectionManager.disconnectAll()
        bluetoothConnectionManager.disconnectAll()
    }
}
