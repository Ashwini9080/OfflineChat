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

/**
 * Manages Wi-Fi Direct P2P group connections for one or more peers.
 *
 * ## Lifecycle
 * - The [BroadcastReceiver] is registered lazily in [connect] and released in [release].
 * - [WifiP2pManager.Channel] is initialized once and re-created if the framework disconnects it.
 * - All connection state changes driven by [WIFI_P2P_CONNECTION_CHANGED_ACTION] broadcasts.
 *
 * ## Group Owner / Client handling
 * Android negotiates group owner vs client autonomously. [WifiDirectPeerConnection.onConnected]
 * receives the actual [WifiP2pInfo] with `isGroupOwner` and `groupOwnerAddress` so the application
 * can later open a local socket on the correct address (Phase 9+).
 *
 * ## Thread safety
 * [ConcurrentHashMap] for active connections; [AtomicBoolean] for receiver registration guard.
 */
@Singleton
class WifiDirectConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: WifiDirectPermissionHelper,
) {
    companion object {
        private const val TAG = "WifiDirectConnMgr"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val p2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    /** Peer ID → active connection object. */
    private val activeConnections = ConcurrentHashMap<String, WifiDirectPeerConnection>()

    /** Peer ID → observable connection state. */
    private val connectionStates = ConcurrentHashMap<String, MutableStateFlow<PeerConnectionState>>()

    /** Guards against duplicate BroadcastReceiver registration. */
    private val isReceiverRegistered = AtomicBoolean(false)

    /**
     * Tracks the peer we are currently establishing a connection to.
     * Used to match incoming [WIFI_P2P_CONNECTION_CHANGED_ACTION] broadcasts.
     */
    @Volatile
    private var pendingConnectingPeerId: String? = null

    // ── BroadcastReceiver ─────────────────────────────────────────────────────

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    handleConnectionChanged(intent)
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "Wi-Fi P2P state: enabled=$isEnabled")
                    if (!isEnabled) {
                        disconnectAllInternal(PeerConnectionState.WifiDisabled)
                    }
                }
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun initializeChannel() {
        if (p2pManager != null && channel == null) {
            channel = p2pManager.initialize(context, context.mainLooper) {
                Log.w(TAG, "Wi-Fi Direct channel disconnected by framework — clearing")
                channel = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionChanged(intent: Intent) {
        val networkInfo: NetworkInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
        }

        val manager = p2pManager ?: return
        val ch = channel ?: return

        Log.d(TAG, "WIFI_P2P_CONNECTION_CHANGED: networkConnected=${networkInfo?.isConnected}")

        if (networkInfo != null && networkInfo.isConnected) {
            manager.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                if (info == null) {
                    Log.w(TAG, "requestConnectionInfo returned null — ignoring")
                    return@requestConnectionInfo
                }
                manager.requestGroupInfo(ch) { group: WifiP2pGroup? ->
                    Log.i(
                        TAG,
                        "Wi-Fi Direct group formed: groupFormed=${info.groupFormed}, " +
                                "isGO=${info.isGroupOwner}, " +
                                "goAddr=${info.groupOwnerAddress?.hostAddress}"
                    )
                    val otherAddress = if (info.isGroupOwner) {
                        group?.clientList?.firstOrNull()?.deviceAddress
                    } else {
                        group?.owner?.deviceAddress
                    }
                    val fallbackPeerId = otherAddress?.replace(":", "")?.lowercase()

                    val targetPeerId = pendingConnectingPeerId
                        ?: activeConnections.keys.firstOrNull()
                        ?: fallbackPeerId

                    if (targetPeerId != null) {
                        val conn = activeConnections.getOrPut(targetPeerId) {
                            val newConn = WifiDirectPeerConnection(targetPeerId, manager, ch)
                            scope.launch {
                                newConn.connectionState.collect { state ->
                                    getOrCreateStateFlow(targetPeerId).value = state
                                }
                            }
                            newConn
                        }
                        conn.onConnected(info, group)
                        getOrCreateStateFlow(targetPeerId).value = PeerConnectionState.Connected
                        Log.i(TAG, "Wi-Fi Direct peer connected successfully: $targetPeerId")
                    } else {
                        Log.w(TAG, "No pending peer ID to associate the connection with")
                    }
                }
            }
        } else {
            // Connection dropped / disconnected
            val targetPeerId = pendingConnectingPeerId
                ?: activeConnections.keys.firstOrNull()
            if (targetPeerId != null) {
                Log.i(TAG, "Wi-Fi Direct disconnected for peer: $targetPeerId")
                activeConnections[targetPeerId]?.onDisconnected()
                getOrCreateStateFlow(targetPeerId).value = PeerConnectionState.Disconnected
            }
            pendingConnectingPeerId = null
        }
    }

    private fun getOrCreateStateFlow(peerId: String): MutableStateFlow<PeerConnectionState> =
        connectionStates.getOrPut(peerId) { MutableStateFlow(PeerConnectionState.Idle) }

    // ── Public API ────────────────────────────────────────────────────────────

    fun observeConnectionState(peerId: String): Flow<PeerConnectionState> =
        getOrCreateStateFlow(peerId).asStateFlow()

    fun getConnectionState(peerId: String): PeerConnectionState =
        getOrCreateStateFlow(peerId).value

    fun isConnected(peerId: String): Boolean =
        activeConnections[peerId]?.isConnected == true ||
                connectionStates[peerId]?.value is PeerConnectionState.Connected

    fun getActiveConnection(peerId: String): PeerConnection? =
        activeConnections[peerId]

    fun getConnectionInfo(peerId: String): WifiDirectConnectionInfo? =
        activeConnections[peerId]?.connectionInfo?.value

    /**
     * Validates state, initializes the channel lazily, registers the receiver,
     * and delegates to [WifiDirectPeerConnection.connect].
     */
    suspend fun connect(peer: Peer): Result<Unit> {
        if (!permissionHelper.isWifiDirectSupported() || p2pManager == null) {
            val msg = "Wi-Fi Direct is not supported on this device"
            getOrCreateStateFlow(peer.deviceId).value = PeerConnectionState.ConnectionFailed(msg)
            return Result.failure(IllegalStateException(msg))
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
            val msg = "Wi-Fi P2P channel initialization failed"
            getOrCreateStateFlow(peer.deviceId).value = PeerConnectionState.ConnectionFailed(msg)
            return Result.failure(IllegalStateException(msg))
        }

        // Register the receiver before attempting connection
        registerReceiver()
        pendingConnectingPeerId = peer.deviceId

        val connection = activeConnections.getOrPut(peer.deviceId) {
            WifiDirectPeerConnection(peer.deviceId, p2pManager, ch)
        }

        // Bridge per-connection state into the shared map
        scope.launch {
            connection.connectionState.collect { state ->
                getOrCreateStateFlow(peer.deviceId).value = state
            }
        }

        return connection.connect(peer)
    }

    suspend fun cancelConnection(peerId: String) {
        Log.i(TAG, "Cancelling Wi-Fi Direct connection for: $peerId")
        activeConnections[peerId]?.disconnect()
        if (pendingConnectingPeerId == peerId) {
            pendingConnectingPeerId = null
        }
        getOrCreateStateFlow(peerId).value = PeerConnectionState.Disconnected
    }

    suspend fun disconnect(peerId: String) {
        Log.i(TAG, "Disconnecting Wi-Fi Direct peer: $peerId")
        val conn = activeConnections.remove(peerId)
        conn?.disconnect()
        getOrCreateStateFlow(peerId).value = PeerConnectionState.Disconnected
    }

    suspend fun disconnectAll() {
        disconnectAllInternal(PeerConnectionState.Disconnected)
    }

    private fun disconnectAllInternal(targetState: PeerConnectionState) {
        activeConnections.forEach { (peerId, conn) ->
            scope.launch { conn.disconnect() }
            getOrCreateStateFlow(peerId).value = targetState
        }
        activeConnections.clear()
        pendingConnectingPeerId = null
    }

    // ── Receiver lifecycle ────────────────────────────────────────────────────

    private fun registerReceiver() {
        if (isReceiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(connectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(connectionReceiver, filter)
                }
                Log.d(TAG, "Wi-Fi Direct connection receiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register connection receiver", e)
                isReceiverRegistered.set(false)
            }
        }
    }

    /**
     * Unregisters the BroadcastReceiver and releases the P2P channel.
     * Must be called when the component is no longer needed (e.g., app lifecycle end).
     */
    fun release() {
        if (isReceiverRegistered.compareAndSet(true, false)) {
            try {
                context.unregisterReceiver(connectionReceiver)
                Log.d(TAG, "Wi-Fi Direct connection receiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Connection receiver was already unregistered")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            channel?.close()
        }
        channel = null
    }
}
