package com.offlinechat.data.discovery

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Wi-Fi Direct device discovery implementation utilizing Android's native [WifiP2pManager].
 *
 * Implements the required lifecycle:
 * IDLE -> CHECKING_WIFI -> CHECKING_PERMISSION -> STARTING_DISCOVERY -> DISCOVERING -> PEERS_FOUND -> DISCOVERY_COMPLETE
 *
 * Translates Android [WifiP2pDevice] framework instances into clean [Peer] domain objects with
 * [TransportType.WIFI_DIRECT], completely isolating hardware frameworks from upper layers.
 */
@Singleton
class WifiDirectDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: WifiDirectPermissionHelper
) : DeviceDiscovery {

    companion object {
        private const val TAG = "WifiDirectDiscovery"
    }

    override val transportType: TransportType = TransportType.WIFI_DIRECT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val p2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    override val discoveredPeers: Flow<List<Peer>> = _discoveredPeers.asStateFlow()

    private val _discoveryStatus = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Idle)
    override val discoveryStatus: Flow<DiscoveryStatus> = _discoveryStatus.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: Flow<Boolean> = _isDiscovering.asStateFlow()

    private val isReceiverRegistered = AtomicBoolean(false)

    init {
        initializeChannel()
    }

    private fun initializeChannel() {
        if (p2pManager != null && channel == null) {
            channel = p2pManager.initialize(context, context.mainLooper) {
                Log.w(TAG, "Wi-Fi P2P Channel disconnected. Re-initializing...")
                channel = null
            }
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "WIFI_P2P_STATE_CHANGED_ACTION: isP2pEnabled=$isP2pEnabled")
                    if (!isP2pEnabled) {
                        _discoveryStatus.value = DiscoveryStatus.WifiDisabled
                        _isDiscovering.value = false
                        _discoveredPeers.value = emptyList()
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION received, requesting peers")
                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val discoveryState = intent.getIntExtra(
                        WifiP2pManager.EXTRA_DISCOVERY_STATE,
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
                    )
                    val isRunning = discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    Log.d(TAG, "WIFI_P2P_DISCOVERY_CHANGED_ACTION: isRunning=$isRunning")
                    _isDiscovering.value = isRunning
                    if (!isRunning && _discoveryStatus.value is DiscoveryStatus.Discovering) {
                        _discoveryStatus.value = DiscoveryStatus.DiscoveryComplete
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery(localDisplayName: String, localDeviceId: String): Result<Unit> {
        // 1. Check Wi-Fi Direct hardware capability
        if (!permissionHelper.isWifiDirectSupported() || p2pManager == null) {
            Log.w(TAG, "Wi-Fi Direct is not supported on this hardware")
            _discoveryStatus.value = DiscoveryStatus.WifiDirectUnsupported
            return Result.failure(IllegalStateException("Wi-Fi Direct is unsupported on this device"))
        }

        // 2. Check Wi-Fi enabled state
        _discoveryStatus.value = DiscoveryStatus.CheckingWifi
        if (!permissionHelper.isWifiEnabled()) {
            Log.w(TAG, "Wi-Fi is turned off")
            _discoveryStatus.value = DiscoveryStatus.WifiDisabled
            return Result.failure(IllegalStateException("Wi-Fi is turned off"))
        }

        // 3. Check runtime permissions
        _discoveryStatus.value = DiscoveryStatus.CheckingPermissions
        if (!permissionHelper.hasRequiredPermissions()) {
            val missing = permissionHelper.getMissingPermissions()
            Log.w(TAG, "Missing required Wi-Fi Direct permissions: $missing")
            _discoveryStatus.value = DiscoveryStatus.PermissionRequired(missing)
            return Result.failure(SecurityException("Missing required Wi-Fi Direct permissions"))
        }

        initializeChannel()
        val ch = channel ?: run {
            _discoveryStatus.value = DiscoveryStatus.DiscoveryFailed("Failed to initialize Wi-Fi P2P channel")
            return Result.failure(IllegalStateException("Wi-Fi P2P channel initialization failed"))
        }

        // 4. Register BroadcastReceiver
        registerReceiver()

        _discoveryStatus.value = DiscoveryStatus.StartingDiscovery

        // 5. Initiate real native peer discovery
        return try {
            p2pManager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct discoverPeers started successfully")
                    _isDiscovering.value = true
                    _discoveryStatus.value = DiscoveryStatus.Discovering
                    requestPeers()
                }

                override fun onFailure(reasonCode: Int) {
                    val reason = formatReason(reasonCode)
                    Log.w(TAG, "Wi-Fi Direct discoverPeers failed: $reason")
                    _isDiscovering.value = false
                    _discoveryStatus.value = DiscoveryStatus.DiscoveryFailed(reason)
                }
            })
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting Wi-Fi Direct peer discovery", e)
            _isDiscovering.value = false
            _discoveryStatus.value = DiscoveryStatus.DiscoveryFailed(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        Log.i(TAG, "Stopping Wi-Fi Direct peer discovery")
        _isDiscovering.value = false
        _discoveryStatus.value = DiscoveryStatus.DiscoveryComplete

        val manager = p2pManager ?: return
        val ch = channel ?: return

        try {
            manager.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "stopPeerDiscovery succeeded")
                }

                override fun onFailure(reason: Int) {
                    Log.d(TAG, "stopPeerDiscovery failed: ${formatReason(reason)}")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Error calling stopPeerDiscovery", e)
        }

        unregisterReceiver()
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val manager = p2pManager ?: return
        val ch = channel ?: return

        manager.requestPeers(ch) { peers: WifiP2pDeviceList? ->
            val deviceList = peers?.deviceList ?: emptyList()
            Log.d(TAG, "Discovered ${deviceList.size} Wi-Fi Direct peers")

            val mapped = deviceList.map { device: WifiP2pDevice ->
                Peer(
                    deviceId = device.deviceAddress,
                    displayName = device.deviceName?.ifBlank { "Wi-Fi Peer" } ?: "Wi-Fi Peer",
                    bluetoothAddress = null,
                    transportType = TransportType.WIFI_DIRECT,
                    lastSeenAt = System.currentTimeMillis()
                )
            }.distinctBy { it.deviceId }

            _discoveredPeers.value = mapped
            if (mapped.isNotEmpty()) {
                _discoveryStatus.value = DiscoveryStatus.DeviceFound(mapped.size)
            }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(p2pReceiver, filter)
            }
        }
    }

    private fun unregisterReceiver() {
        if (isReceiverRegistered.compareAndSet(true, false)) {
            try {
                context.unregisterReceiver(p2pReceiver)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Receiver already unregistered")
            }
        }
    }

    private fun formatReason(reasonCode: Int): String {
        return when (reasonCode) {
            WifiP2pManager.ERROR -> "P2P System Error"
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct Unsupported"
            WifiP2pManager.BUSY -> "Framework Busy"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "No Service Requests"
            else -> "Error code: $reasonCode"
        }
    }
}
