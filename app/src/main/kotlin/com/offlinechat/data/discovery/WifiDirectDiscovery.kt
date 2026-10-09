package com.offlinechat.data.discovery

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.offlinechat.domain.model.DiscoveryStatus
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Wi-Fi Direct device discovery using Android's native [WifiP2pManager].
 *
 * ## Discovery lifecycle
 * ```
 * Idle → CheckingWifi → CheckingPermissions → StartingDiscovery → Discovering → DeviceFound → DiscoveryComplete
 * ```
 * Failure states: WifiDisabled, WifiDirectUnsupported, PermissionRequired, DiscoveryFailed, DiscoveryCancelled
 *
 * ## Identity contract
 * Discovered [Peer] objects carry a temporary Wi-Fi MAC-based ID for routing only.
 * Cryptographic identity is established later via the Phase 7 secure handshake.
 *
 * ## Lifecycle safety
 * - The BroadcastReceiver is registered only when discovery is active (not at construction).
 * - [AtomicBoolean] guards against duplicate registration.
 * - [stopDiscovery] always unregisters the receiver and updates state.
 */
@Singleton
class WifiDirectDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: WifiDirectPermissionHelper,
    private val deviceMapper: WifiDirectDeviceMapper,
) : DeviceDiscovery {

    companion object {
        private const val TAG = "WifiDirectDiscovery"
    }

    override val transportType: TransportType = TransportType.WIFI_DIRECT

    @Suppress("unused")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val p2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    override val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    private val _discoveryStatus = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Idle)
    override val discoveryStatus: StateFlow<DiscoveryStatus> = _discoveryStatus.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    // Guards against duplicate BroadcastReceiver registration
    private val isReceiverRegistered = AtomicBoolean(false)

    // ── BroadcastReceiver ─────────────────────────────────────────────────────

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "Wi-Fi P2P state changed: enabled=$isP2pEnabled")
                    if (!isP2pEnabled) {
                        _discoveryStatus.value = DiscoveryStatus.WifiDisabled
                        _isDiscovering.value = false
                        _discoveredPeers.value = emptyList()
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION received — requesting peer list")
                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val discoveryState = intent.getIntExtra(
                        WifiP2pManager.EXTRA_DISCOVERY_STATE,
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
                    )
                    val isRunning = discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    Log.d(TAG, "Discovery state changed: running=$isRunning")
                    _isDiscovering.value = isRunning
                    if (!isRunning && _discoveryStatus.value is DiscoveryStatus.Discovering) {
                        _discoveryStatus.value = DiscoveryStatus.DiscoveryComplete
                    }
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery(
        localDisplayName: String,
        localDeviceId: String,
    ): Result<Unit> {

        // 1. Check hardware capability
        if (!permissionHelper.isWifiDirectSupported() || p2pManager == null) {
            Log.w(TAG, "Wi-Fi Direct is not supported on this hardware")
            _discoveryStatus.value = DiscoveryStatus.WifiDirectUnsupported
            return Result.failure(IllegalStateException("Wi-Fi Direct is unsupported on this device"))
        }

        // 2. Check Wi-Fi enabled state
        _discoveryStatus.value = DiscoveryStatus.CheckingWifi
        if (!permissionHelper.isWifiEnabled()) {
            Log.w(TAG, "Wi-Fi is turned off — cannot start Wi-Fi Direct discovery")
            _discoveryStatus.value = DiscoveryStatus.WifiDisabled
            return Result.failure(IllegalStateException("Wi-Fi is turned off"))
        }

        // 3. Check runtime permissions
        _discoveryStatus.value = DiscoveryStatus.CheckingPermissions
        if (!permissionHelper.hasRequiredPermissions()) {
            val missing = permissionHelper.getMissingPermissions()
            Log.w(TAG, "Missing Wi-Fi Direct permissions: $missing")
            _discoveryStatus.value = DiscoveryStatus.PermissionRequired(missing)
            return Result.failure(SecurityException("Missing required Wi-Fi Direct permissions: $missing"))
        }

        // 4. Ensure the P2P channel is initialized
        initializeChannel()
        val ch = channel ?: run {
            val msg = "Wi-Fi P2P channel initialization failed"
            Log.e(TAG, msg)
            _discoveryStatus.value = DiscoveryStatus.DiscoveryFailed(msg)
            return Result.failure(IllegalStateException(msg))
        }

        // 5. Register BroadcastReceiver (only when active — not in init)
        registerReceiver()

        _discoveryStatus.value = DiscoveryStatus.StartingDiscovery

        // 6. Start native Wi-Fi Direct peer discovery
        return try {
            p2pManager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "discoverPeers() accepted by framework — discovery starting")
                    _isDiscovering.value = true
                    _discoveryStatus.value = DiscoveryStatus.Discovering
                    // Request any already-known peers immediately
                    requestPeers()
                }

                override fun onFailure(reasonCode: Int) {
                    val reason = formatFailureReason(reasonCode)
                    Log.w(TAG, "discoverPeers() failed: $reason (code=$reasonCode)")
                    _isDiscovering.value = false
                    _discoveryStatus.value = DiscoveryStatus.DiscoveryFailed(reason)
                    unregisterReceiver()
                }
            })
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during discoverPeers — permission revoked", e)
            _isDiscovering.value = false
            _discoveryStatus.value = DiscoveryStatus.PermissionRevoked
            unregisterReceiver()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception starting Wi-Fi Direct discovery", e)
            _isDiscovering.value = false
            _discoveryStatus.value = DiscoveryStatus.DiscoveryFailed(e.message ?: "Unknown error")
            unregisterReceiver()
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        Log.i(TAG, "Stopping Wi-Fi Direct discovery")
        _isDiscovering.value = false

        val manager = p2pManager
        val ch = channel

        if (manager != null && ch != null) {
            try {
                manager.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "stopPeerDiscovery() succeeded")
                    }
                    override fun onFailure(reason: Int) {
                        Log.d(TAG, "stopPeerDiscovery() failed: ${formatFailureReason(reason)}")
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Exception calling stopPeerDiscovery", e)
            }
        }

        // Always update status and unregister — even if stopPeerDiscovery failed
        _discoveryStatus.value = DiscoveryStatus.DiscoveryCancelled
        unregisterReceiver()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun initializeChannel() {
        if (p2pManager != null && channel == null) {
            channel = p2pManager.initialize(context, context.mainLooper) {
                // Framework called channelDisconnectedListener — re-initialize
                Log.w(TAG, "Wi-Fi P2P channel disconnected. Clearing for re-initialization.")
                channel = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val manager = p2pManager ?: return
        val ch = channel ?: return

        try {
            manager.requestPeers(ch) { peers: WifiP2pDeviceList? ->
                val deviceList = peers?.deviceList ?: emptyList()
                Log.d(TAG, "requestPeers() returned ${deviceList.size} devices")

                val mapped = deviceMapper.mapDeviceList(deviceList)
                _discoveredPeers.value = mapped

                if (mapped.isNotEmpty()) {
                    _discoveryStatus.value = DiscoveryStatus.DeviceFound(mapped.size)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException during requestPeers — permission may have been revoked", e)
            _discoveryStatus.value = DiscoveryStatus.PermissionRevoked
        }
    }

    private fun registerReceiver() {
        if (isReceiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(p2pReceiver, filter)
                }
                Log.d(TAG, "Wi-Fi Direct discovery receiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register Wi-Fi Direct receiver", e)
                isReceiverRegistered.set(false)
            }
        }
    }

    private fun unregisterReceiver() {
        if (isReceiverRegistered.compareAndSet(true, false)) {
            try {
                context.unregisterReceiver(p2pReceiver)
                Log.d(TAG, "Wi-Fi Direct discovery receiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Wi-Fi Direct receiver was already unregistered")
            }
        }
    }

    private fun formatFailureReason(reasonCode: Int): String = when (reasonCode) {
        WifiP2pManager.ERROR           -> "Internal P2P framework error"
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
        WifiP2pManager.BUSY            -> "Wi-Fi Direct framework is busy — try again"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "No service requests registered"
        else                           -> "Unknown error (code $reasonCode)"
    }
}
