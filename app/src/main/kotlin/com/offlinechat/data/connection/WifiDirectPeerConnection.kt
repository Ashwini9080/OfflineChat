package com.offlinechat.data.connection

import android.annotation.SuppressLint
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.offlinechat.domain.connection.PeerConnection
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.WifiDirectConnectionInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete [PeerConnection] implementation managing a direct 1-to-1 Wi-Fi Direct connection.
 *
 * Responsibilities:
 * 1. Coordinates connection initiation via [WifiP2pManager.connect].
 * 2. Handles Group Owner (GO) vs Group Client negotiation dynamically.
 * 3. Inspects and exposes [WifiDirectConnectionInfo] (group owner IP, passphrase, client count).
 * 4. Gracefully manages connection teardown via [WifiP2pManager.removeGroup].
 */
class WifiDirectPeerConnection(
    override val peerId: String,
    private val p2pManager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) : PeerConnection {

    companion object {
        private const val TAG = "WifiDirectPeerConn"
        private const val CONNECTION_TIMEOUT_MS = 25000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<PeerConnectionState>(PeerConnectionState.Idle)
    override val connectionState: StateFlow<PeerConnectionState> = _connectionState.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiDirectConnectionInfo?>(null)
    val connectionInfo: StateFlow<WifiDirectConnectionInfo?> = _connectionInfo.asStateFlow()

    override val isConnected: Boolean
        get() = _connectionState.value is PeerConnectionState.Connected

    private val isConnecting = AtomicBoolean(false)
    private var pendingConnectResult: CompletableDeferred<Result<Unit>>? = null

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: Peer): Result<Unit> {
        if (isConnected) {
            Log.d(TAG, "Already connected to peer: $peerId")
            return Result.success(Unit)
        }

        if (!isConnecting.compareAndSet(false, true)) {
            Log.w(TAG, "Connection attempt already in progress for peer: $peerId")
            return Result.failure(IllegalStateException("Connection already in progress"))
        }

        _connectionState.value = PeerConnectionState.Connecting
        Log.i(TAG, "Initiating Wi-Fi Direct connection to: ${peer.displayName} [$peerId]")

        val deferredResult = CompletableDeferred<Result<Unit>>()
        pendingConnectResult = deferredResult

        val targetMac = formatMacAddress(peer.deviceId)
        Log.d(TAG, "Connecting to target MAC: $targetMac (from peerId: ${peer.deviceId})")

        val config = WifiP2pConfig().apply {
            deviceAddress = targetMac
            wps.setup = WpsInfo.PBC
        }

        try {
            p2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "WifiP2pManager.connect request accepted. Waiting for group formation...")
                    // Group formation and final connection status are signaled via broadcast receiver
                }

                override fun onFailure(reasonCode: Int) {
                    val reason = formatReason(reasonCode)
                    Log.w(TAG, "WifiP2pManager.connect failed: $reason")
                    isConnecting.set(false)
                    _connectionState.value = PeerConnectionState.ConnectionFailed(reason)
                    deferredResult.complete(Result.failure(Exception("Connection failed: $reason")))
                }
            })

            // Await connection result with timeout
            val result = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                deferredResult.await()
            }

            return if (result != null) {
                result
            } else {
                Log.w(TAG, "Wi-Fi Direct connection timed out after ${CONNECTION_TIMEOUT_MS}ms")
                isConnecting.set(false)
                cancelConnection()
                _connectionState.value = PeerConnectionState.ConnectionTimeout(CONNECTION_TIMEOUT_MS)
                Result.failure(Exception("Wi-Fi Direct connection timeout"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Wi-Fi Direct connection to $peerId", e)
            isConnecting.set(false)
            _connectionState.value = PeerConnectionState.ConnectionFailed(e.message ?: "Unknown error")
            return Result.failure(e)
        }
    }

    /**
     * Called when the group forms and connection info becomes available from the framework.
     */
    fun onConnected(info: WifiP2pInfo, group: WifiP2pGroup?) {
        isConnecting.set(false)

        val isGo = info.isGroupOwner
        val goAddress = info.groupOwnerAddress?.hostAddress
        val groupFormed = info.groupFormed
        val clientCount = group?.clientList?.size ?: (if (isGo) 1 else 0)

        Log.i(
            TAG,
            "Wi-Fi Direct connected! isGroupOwner=$isGo, groupOwnerAddress=$goAddress, clients=$clientCount"
        )

        val domainInfo = WifiDirectConnectionInfo(
            groupFormed = groupFormed,
            isGroupOwner = isGo,
            groupOwnerAddress = goAddress,
            networkName = group?.networkName,
            passphrase = group?.passphrase,
            clientCount = clientCount
        )

        _connectionInfo.value = domainInfo
        _connectionState.value = PeerConnectionState.Connected
        pendingConnectResult?.complete(Result.success(Unit))
    }

    /**
     * Called when the Wi-Fi Direct connection drops or is lost.
     */
    fun onDisconnected() {
        isConnecting.set(false)
        _connectionInfo.value = null
        if (_connectionState.value !is PeerConnectionState.Idle) {
            _connectionState.value = PeerConnectionState.Disconnected
        }
        pendingConnectResult?.complete(Result.failure(Exception("Connection lost")))
    }

    @SuppressLint("MissingPermission")
    private fun cancelConnection() {
        try {
            p2pManager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "cancelConnect succeeded")
                }

                override fun onFailure(reason: Int) {
                    Log.d(TAG, "cancelConnect failed: ${formatReason(reason)}")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling connect", e)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        if (_connectionState.value is PeerConnectionState.Disconnected ||
            _connectionState.value is PeerConnectionState.Idle
        ) {
            return
        }

        _connectionState.value = PeerConnectionState.Disconnecting
        Log.i(TAG, "Disconnecting Wi-Fi Direct peer: $peerId")

        try {
            p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "removeGroup succeeded for peer: $peerId")
                    onDisconnected()
                }

                override fun onFailure(reasonCode: Int) {
                    Log.w(TAG, "removeGroup failed: ${formatReason(reasonCode)}")
                    onDisconnected()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception during removeGroup", e)
            onDisconnected()
        }
    }

    override fun release() {
        scope.launch {
            disconnect()
        }
    }

    private fun formatReason(reasonCode: Int): String {
        return when (reasonCode) {
            WifiP2pManager.ERROR -> "P2P System Error"
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct Unsupported"
            WifiP2pManager.BUSY -> "Framework Busy"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "No Service Requests"
            else -> "Error code $reasonCode"
        }
    }

    private fun formatMacAddress(address: String): String {
        val clean = address.trim()
        if (clean.contains(":")) return clean
        if (clean.length == 12) {
            return clean.chunked(2).joinToString(":")
        }
        return clean
    }
}
