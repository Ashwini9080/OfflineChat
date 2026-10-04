package com.offlinechat.data.connection

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.offlinechat.data.discovery.WifiDirectPermissionHelper
import com.offlinechat.domain.connection.PeerConnection
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.WifiDirectConnectionInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiDirectConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: WifiDirectPermissionHelper
) {

    companion object {
        private const val TAG = "WifiDirectConnMgr"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val activeConnections = ConcurrentHashMap<String, WifiDirectPeerConnection>()
    private val connectionStates = ConcurrentHashMap<String, MutableStateFlow<PeerConnectionState>>()
    private val isReceiverRegistered = AtomicBoolean(false)

    // Tracks currently connecting peer ID to match connection info
    private var pendingConnectingPeerId: String? = null

    init {
        initializeChannel()
        registerReceiver()
    }

    private fun initializeChannel() {
        if (p2pManager != null && channel == null) {
            channel = p2pManager.initialize(context, context.mainLooper) {
                Log.w(TAG, "Wi-Fi Direct channel disconnected. Re-initializing...")
                channel = null
            }
        }
    }

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    handleConnectionChanged(intent)
                }

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "WIFI_P2P_STATE_CHANGED_ACTION: isEnabled=$isEnabled")
                    if (!isEnabled) {
                        disconnectAllInternal(PeerConnectionState.WifiDisabled)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionChanged(intent: Intent) {
        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
        }

        val manager = p2pManager ?: return
        val ch = channel ?: return

        Log.d(TAG, "handleConnectionChanged: networkInfo=$networkInfo, isConnected=${networkInfo?.isConnected}")

        if (networkInfo != null && networkInfo.isConnected) {
            manager.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                if (info == null) return@requestConnectionInfo

                manager.requestGroupInfo(ch) { group: WifiP2pGroup? ->
                    Log.i(TAG, "Wi-Fi Direct connection established! groupFormed=${info.groupFormed}, isGO=${info.isGroupOwner}")

                    // Forward to active peer connection
                    val targetPeerId = pendingConnectingPeerId ?: activeConnections.keys.firstOrNull()
                    if (targetPeerId != null) {
                        val connection = activeConnections[targetPeerId]
                        connection?.onConnected(info, group)
                        getOrCreateStateFlow(targetPeerId).value = PeerConnectionState.Connected
                    }
                }
            }
        } else {
            // Connection lost or dropped
            val targetPeerId = pendingConnectingPeerId ?: activeConnections.keys.firstOrNull()
            if (targetPeerId != null) {
                activeConnections[targetPeerId]?.onDisconnected()
                getOrCreateStateFlow(targetPeerId).value = PeerConnectionState.Disconnected
            }
            pendingConnectingPeerId = null
        }
    }

    fun observeConnectionState(peerId: String): Flow<PeerConnectionState> {
        return getOrCreateStateFlow(peerId).asStateFlow()
    }

    fun getConnectionState(peerId: String): PeerConnectionState {
        return getOrCreateStateFlow(peerId).value
    }

    fun isConnected(peerId: String): Boolean {
        return activeConnections[peerId]?.isConnected == true ||
                connectionStates[peerId]?.value is PeerConnectionState.Connected
    }

    fun getActiveConnection(peerId: String): PeerConnection? {
        return activeConnections[peerId]
    }

    fun getConnectionInfo(peerId: String): WifiDirectConnectionInfo? {
        return activeConnections[peerId]?.connectionInfo?.value
    }

    suspend fun connect(peer: Peer): Result<Unit> {
        if (!permissionHelper.isWifiDirectSupported() || p2pManager == null) {
            getOrCreateStateFlow(peer.deviceId).value = PeerConnectionState.ConnectionFailed("Wi-Fi Direct unsupported")
            return Result.failure(IllegalStateException("Wi-Fi Direct unsupported on this device"))
        }

        if (!permissionHelper.isWifiEnabled()) {
            getOrCreateStateFlow(peer.deviceId).value = PeerConnectionState.WifiDisabled
            return Result.failure(IllegalStateException("Wi-Fi is turned off"))
        }

        if (!permissionHelper.hasRequiredPermissions()) {
            getOrCreateStateFlow(peer.deviceId).value = PeerConnectionState.PermissionRevoked
            return Result.failure(SecurityException("Missing required Wi-Fi Direct permissions"))
        }

        initializeChannel()
        val ch = channel ?: run {
            getOrCreateStateFlow(peer.deviceId).value = PeerConnectionState.ConnectionFailed("Channel initialization failed")
            return Result.failure(IllegalStateException("Wi-Fi P2P channel initialization failed"))
        }

        registerReceiver()
        pendingConnectingPeerId = peer.deviceId

        val connection = activeConnections.getOrPut(peer.deviceId) {
            WifiDirectPeerConnection(peer.deviceId, p2pManager, ch)
        }

        // Bridge state updates
        scope.launch {
            connection.connectionState.collect { state ->
                getOrCreateStateFlow(peer.deviceId).value = state
            }
        }

        return connection.connect(peer)
    }

    suspend fun cancelConnection(peerId: String) {
        val conn = activeConnections[peerId]
        conn?.disconnect()
        if (pendingConnectingPeerId == peerId) {
            pendingConnectingPeerId = null
        }
    }

    suspend fun disconnect(peerId: String) {
        val conn = activeConnections.remove(peerId)
        conn?.disconnect()
        getOrCreateStateFlow(peerId).value = PeerConnectionState.Disconnected
    }

    suspend fun disconnectAll() {
        disconnectAllInternal(PeerConnectionState.Disconnected)
    }

    private fun disconnectAllInternal(targetState: PeerConnectionState) {
        activeConnections.forEach { (peerId, conn) ->
            scope.launch {
                conn.disconnect()
            }
            getOrCreateStateFlow(peerId).value = targetState
        }
        activeConnections.clear()
        pendingConnectingPeerId = null
    }

    private fun getOrCreateStateFlow(peerId: String): MutableStateFlow<PeerConnectionState> {
        return connectionStates.getOrPut(peerId) {
            MutableStateFlow(PeerConnectionState.Idle)
        }
    }

    private fun registerReceiver() {
        if (isReceiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(connectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(connectionReceiver, filter)
            }
        }
    }

    fun release() {
        if (isReceiverRegistered.compareAndSet(true, false)) {
            try {
                context.unregisterReceiver(connectionReceiver)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Connection receiver already unregistered")
            }
        }
    }
}
