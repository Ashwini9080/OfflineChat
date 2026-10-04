package com.offlinechat.transport.manager

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates all available [Transport] implementations.
 *
 * ## Responsibilities
 * 1. Start/stop all transports together with the app lifecycle.
 * 2. Merge event flows from all transports into a single [events] flow for
 *    the :messaging module to consume.
 * 3. Route [connect] calls to the best available transport for a given peer.
 * 4. Maintain a registry of open [TransportChannel]s for message delivery.
 *
 * ## Transport selection strategy
 * When [connect] is called for a peer that is reachable over multiple transports,
 * [TransportManager] prefers them in this order:
 * ```
 * Wi-Fi Direct > Local Wi-Fi > Bluetooth
 * ```
 * This preference is implemented by [selectTransport] and can be changed without
 * modifying [BluetoothTransport] or [WiFiDirectTransport].
 *
 * ## Scope
 * [TransportManager] creates its own [SupervisorJob] scope so transport failures
 * do not cascade to the application scope. Failing transports emit a
 * [TransportEvent.TransportError] instead of crashing.
 */
@Singleton
class TransportManager @Inject constructor(
    private val bluetoothTransport: BluetoothTransport,
    private val wifiDirectTransport: WiFiDirectTransport,
) {
    // All transports in preference order (highest preference last — reversed when selecting)
    private val allTransports: List<Transport> = listOf(
        bluetoothTransport,
        wifiDirectTransport,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<TransportEvent>(
        replay = 0,
        extraBufferCapacity = 128,
    )

    /** Unified event stream from all active transports. */
    val events: Flow<TransportEvent> = _events.asSharedFlow()

    /** All currently open channels, keyed by peer device ID. */
    private val openChannels = mutableMapOf<String, TransportChannel>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts all transports: advertising this device and scanning for peers.
     *
     * Safe to call multiple times — subsequent calls are no-ops if already started.
     *
     * @param identity Local device identity used for advertisement payloads.
     */
    suspend fun start(identity: DeviceIdentity) {
        // Merge all transport event flows into the shared _events bus
        allTransports.forEach { transport ->
            transport.events()
                .onEach { event ->
                    handleTransportEvent(event)
                    _events.emit(event)
                }
                .launchIn(scope)
        }

        // Start advertising and discovery on all available transports
        // Failures are soft — if BT is off, the Bluetooth transport fails but
        // Wi-Fi Direct (if available) still starts.
        allTransports.forEach { transport ->
            scope.launch {
                transport.startAdvertising(identity)
                transport.startDiscovery()
            }
        }

        // Start listening for inbound connections on all transports
        allTransports.forEach { transport ->
            transport.listen()
                .onEach { channelResult ->
                    channelResult.onSuccess { channel ->
                        openChannels[channel.peerId] = channel
                    }
                }
                .launchIn(scope)
        }
    }

    /** Shuts down all transports and clears open channels. */
    suspend fun shutdown() {
        allTransports.forEach { it.shutdown() }
        openChannels.values.forEach { channel ->
            runCatching { channel.close() }
        }
        openChannels.clear()
    }

    // ── Connection management ─────────────────────────────────────────────────

    /**
     * Returns an existing open channel to [peerId], or null if none exists.
     *
     * The :messaging layer calls this before attempting a new connection.
     */
    fun getOpenChannel(peerId: String): TransportChannel? =
        openChannels[peerId]?.takeIf { it.isConnected }

    /**
     * Opens a new channel to [peer] using the best available transport.
     *
     * Prefer calling [getOpenChannel] first — only call this if no channel exists.
     *
     * @return [AppResult.Failure] with [AppError.PeerNotReachable] if no transport
     *         can reach this peer.
     */
    suspend fun connect(peer: PeerDevice): AppResult<TransportChannel> {
        // Don't open a second channel if one already exists
        getOpenChannel(peer.deviceId)?.let { return AppResult.Success(it) }

        val transport = selectTransport(peer)
            ?: return AppResult.Failure(AppError.PeerNotReachable)

        val result = transport.connect(peer)
        result.onSuccess { channel ->
            openChannels[peer.deviceId] = channel
        }
        return result
    }

    /** Closes the channel to [peerId] if one is open. */
    suspend fun disconnect(peerId: String) {
        openChannels.remove(peerId)?.close()
    }

    // ── Transport selection ───────────────────────────────────────────────────

    /**
     * Selects the best available transport for [peer].
     *
     * Priority: Wi-Fi Direct > Bluetooth.
     * Currently only Bluetooth is implemented; Wi-Fi Direct will be preferred
     * automatically once [WiFiDirectTransport] is implemented in Phase 3.
     */
    private fun selectTransport(peer: PeerDevice): Transport? =
        when (peer.transport) {
            TransportType.WIFI_DIRECT -> wifiDirectTransport
            TransportType.BLUETOOTH   -> bluetoothTransport
            TransportType.WIFI_LOCAL  -> null // Phase 3+
        }

    // ── Internal event handling ───────────────────────────────────────────────

    private fun handleTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.ChannelClosed -> {
                openChannels.remove(event.peerId)
            }
            is TransportEvent.ChannelOpened -> {
                openChannels[event.peer.deviceId] = event.channel
            }
            else -> { /* Forwarded to consumers as-is */ }
        }
    }
}
