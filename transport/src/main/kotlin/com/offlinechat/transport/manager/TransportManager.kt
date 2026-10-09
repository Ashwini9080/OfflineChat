package com.offlinechat.transport.manager

import android.util.Log
import com.offlinechat.core.model.DeviceIdentity
import com.offlinechat.core.model.TransportType
import com.offlinechat.core.result.AppError
import com.offlinechat.core.result.AppResult
import com.offlinechat.transport.api.PeerDevice
import com.offlinechat.transport.api.Transport
import com.offlinechat.transport.api.TransportChannel
import com.offlinechat.transport.api.TransportEvent
import com.offlinechat.transport.bluetooth.BluetoothTransport
import com.offlinechat.transport.wifi.WiFiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates all available [Transport] implementations.
 *
 * ## Responsibilities
 * 1. Start/stop all transports together with the app lifecycle.
 * 2. Merge event flows from all transports into a single [events] flow for
 *    the :messaging module to consume.
 * 3. Route [connect] calls to the best available transport with graceful fallback.
 * 4. Maintain a thread-safe registry of open [TransportChannel]s.
 * 5. Prevent duplicate collectors, duplicate channels, and simultaneous connection attempts.
 */
@Singleton
class TransportManager @Inject constructor(
    private val bluetoothTransport: BluetoothTransport,
    private val wifiDirectTransport: WiFiDirectTransport,
) {
    companion object {
        private const val TAG = "TransportManager"
    }

    private val allTransports: List<Transport> = listOf(
        wifiDirectTransport,
        bluetoothTransport,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<TransportEvent>(
        replay = 0,
        extraBufferCapacity = 128,
    )

    /** Unified event stream from all active transports. */
    val events: Flow<TransportEvent> = _events.asSharedFlow()

    /** All currently open channels, keyed by peer device ID (thread-safe). */
    private val openChannels = ConcurrentHashMap<String, TransportChannel>()

    /** Guards simultaneous connection attempts to the same peer. */
    private val connectionMutex = Mutex()
    private val inFlightConnections = mutableSetOf<String>()

    /** Tracks background jobs to avoid duplicate collectors and enable clean shutdown. */
    private val isStarted = AtomicBoolean(false)
    private val backgroundJobs = mutableListOf<Job>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts all transports: advertising this device and scanning for peers.
     *
     * Idempotent — subsequent calls while running are no-ops.
     */
    suspend fun start(identity: DeviceIdentity) {
        if (!isStarted.compareAndSet(false, true)) {
            Log.d(TAG, "TransportManager already started — ignoring duplicate start call")
            return
        }

        synchronized(backgroundJobs) {
            backgroundJobs.clear()

            // Merge all transport event flows into the shared _events bus
            allTransports.forEach { transport ->
                val eventJob = transport.events()
                    .onEach { event ->
                        handleTransportEvent(event)
                        _events.emit(event)
                    }
                    .launchIn(scope)
                backgroundJobs.add(eventJob)
            }

            // Start listening for inbound connections on all transports
            allTransports.forEach { transport ->
                val listenJob = transport.listen()
                    .onEach { channelResult ->
                        when (channelResult) {
                            is AppResult.Success -> {
                                val channel = channelResult.data
                                registerChannel(channel.peerId, channel)
                            }
                            is AppResult.Failure -> {
                                Log.w(TAG, "Inbound connection failed on ${transport.type}: ${channelResult.error}")
                                _events.emit(
                                    TransportEvent.ConnectionFailed(
                                        peerId = "inbound",
                                        reason = "Inbound connection failed on ${transport.type}: ${channelResult.error}",
                                    ),
                                )
                            }
                        }
                    }
                    .launchIn(scope)
                backgroundJobs.add(listenJob)
            }
        }

        // Start advertising and discovery on all available transports
        allTransports.forEach { transport ->
            scope.launch {
                transport.startAdvertising(identity)
                transport.startDiscovery()
            }
        }
    }

    /** Shuts down all transports, cancels collectors, and clears open channels. */
    suspend fun shutdown() {
        isStarted.set(false)

        synchronized(backgroundJobs) {
            backgroundJobs.forEach { it.cancel() }
            backgroundJobs.clear()
        }

        allTransports.forEach { transport ->
            runCatching { transport.shutdown() }
        }

        openChannels.values.forEach { channel ->
            runCatching { channel.close() }
        }
        openChannels.clear()

        connectionMutex.withLock {
            inFlightConnections.clear()
        }
    }

    // ── Connection management ─────────────────────────────────────────────────

    /** Returns an existing open channel to [peerId], or null if none exists. */
    fun getOpenChannel(peerId: String): TransportChannel? =
        openChannels[peerId]?.takeIf { it.isConnected }

    /**
     * Opens a new channel to [peer] using the preferred transport,
     * with automatic fallback if the primary transport fails.
     */
    suspend fun connect(peer: PeerDevice): AppResult<TransportChannel> {
        // 1. Check if already connected
        getOpenChannel(peer.deviceId)?.let { return AppResult.Success(it) }

        // 2. Prevent simultaneous connection attempts to the same peer
        val acquired = connectionMutex.withLock {
            if (inFlightConnections.contains(peer.deviceId)) {
                false
            } else {
                inFlightConnections.add(peer.deviceId)
                true
            }
        }

        if (!acquired) {
            Log.w(TAG, "Connection attempt to ${peer.deviceId} is already in flight")
            return AppResult.Failure(AppError.Unknown("Connection attempt already in progress for ${peer.deviceId}"))
        }

        try {
            // Check again under lock
            getOpenChannel(peer.deviceId)?.let { return AppResult.Success(it) }

            return when (peer.transport) {
                TransportType.WIFI_DIRECT -> {
                    // Try Wi-Fi Direct first
                    Log.i(TAG, "Attempting Wi-Fi Direct connection to ${peer.deviceId}...")
                    when (val wifiResult = wifiDirectTransport.connect(peer)) {
                        is AppResult.Success -> {
                            registerChannel(peer.deviceId, wifiResult.data)
                            wifiResult
                        }
                        is AppResult.Failure -> {
                            // Wi-Fi Direct failed — check if Bluetooth fallback is possible
                            Log.w(TAG, "Wi-Fi Direct connection failed: ${wifiResult.error}. Checking fallback to Bluetooth...")
                            if (peer.bluetoothAddress != null) {
                                val fallbackPeer = peer.copy(transport = TransportType.BLUETOOTH)
                                when (val btResult = bluetoothTransport.connect(fallbackPeer)) {
                                    is AppResult.Success -> {
                                        Log.i(TAG, "Bluetooth fallback succeeded for ${peer.deviceId}")
                                        registerChannel(peer.deviceId, btResult.data)
                                        btResult
                                    }
                                    is AppResult.Failure -> {
                                        Log.e(TAG, "Both Wi-Fi Direct and Bluetooth fallback failed for ${peer.deviceId}")
                                        wifiResult // Return initial failure
                                    }
                                }
                            } else {
                                wifiResult
                            }
                        }
                    }
                }

                TransportType.BLUETOOTH -> {
                    // Try Bluetooth first
                    Log.i(TAG, "Attempting Bluetooth connection to ${peer.deviceId}...")
                    when (val btResult = bluetoothTransport.connect(peer)) {
                        is AppResult.Success -> {
                            registerChannel(peer.deviceId, btResult.data)
                            btResult
                        }
                        is AppResult.Failure -> {
                            Log.w(TAG, "Bluetooth connection failed: ${btResult.error}")
                            btResult
                        }
                    }
                }

                TransportType.WIFI_LOCAL -> {
                    AppResult.Failure(AppError.PeerNotReachable)
                }
            }
        } finally {
            connectionMutex.withLock {
                inFlightConnections.remove(peer.deviceId)
            }
        }
    }

    /** Closes the channel to [peerId] if one is open. */
    suspend fun disconnect(peerId: String) {
        openChannels.remove(peerId)?.close()
    }

    private fun registerChannel(peerId: String, channel: TransportChannel) {
        openChannels[peerId]?.let { existing ->
            if (existing !== channel) {
                runCatching { scope.launch { existing.close() } }
            }
        }
        openChannels[peerId] = channel
        Log.i(TAG, "Registered open channel for peer $peerId on transport ${channel.transportType}")
    }

    // ── Internal event handling ───────────────────────────────────────────────

    private fun handleTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.ChannelClosed -> {
                openChannels.remove(event.peerId)
            }
            is TransportEvent.ChannelOpened -> {
                registerChannel(event.peer.deviceId, event.channel)
            }
            else -> { /* Forwarded to consumers as-is */ }
        }
    }
}
