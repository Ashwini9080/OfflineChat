package com.offlinechat.transport.api

import com.offlinechat.core.model.DeviceIdentity
import com.offlinechat.core.model.TransportType
import com.offlinechat.core.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * The primary abstraction over a physical communication transport.
 *
 * ## Design contract
 * - Each transport is responsible for device DISCOVERY and channel ESTABLISHMENT only.
 * - Message encoding, encryption, and routing are handled by the :messaging module.
 * - A [Transport] implementation must be safe to use from multiple coroutines.
 * - [startAdvertising] and [startDiscovery] can be called concurrently —
 *   most transports need to do both simultaneously.
 *
 * ## Adding a new transport (Wi-Fi Direct example)
 * 1. Create `WiFiDirectTransport` implementing [Transport].
 * 2. Register it in [manager.TransportManager].
 * 3. Add `WIFI_DIRECT` to [core.model.TransportType].
 * No other files need to change.
 */
interface Transport {

    /** The type of transport this implementation provides. */
    val type: TransportType

    /**
     * A hot [Flow] of [TransportEvent]s produced by this transport.
     *
     * Callers should collect this in a supervisor scope tied to the transport
     * lifecycle. The flow never completes normally — it runs until [shutdown].
     * Errors are delivered as [TransportEvent.TransportError] rather than
     * flow exceptions, so collection is never interrupted by a single error.
     */
    fun events(): Flow<TransportEvent>

    /**
     * Begins advertising this device's presence to nearby peers.
     *
     * For Bluetooth: starts BLE peripheral advertising using the device identity
     * as manufacturer data payload.
     *
     * @param identity The local device's identity, used to construct the advertisement.
     * @return [AppResult.Success] once advertising starts; [AppResult.Failure] if
     *         the transport is unavailable or permissions are missing.
     */
    suspend fun startAdvertising(identity: DeviceIdentity): AppResult<Unit>

    /** Stops advertising this device's presence. Safe to call even if not advertising. */
    suspend fun stopAdvertising()

    /**
     * Begins scanning for nearby peers advertising via this transport.
     *
     * Discovered peers are emitted as [TransportEvent.PeerDiscovered] events.
     * Lost peers are emitted as [TransportEvent.PeerLost] events.
     *
     * @return [AppResult.Failure] if permissions are missing or transport is off.
     */
    suspend fun startDiscovery(): AppResult<Unit>

    /** Stops scanning. Safe to call even if not scanning. */
    suspend fun stopDiscovery()

    /**
     * Opens a data channel to [peer].
     *
     * For Bluetooth: connects a Classic RFCOMM socket to the peer's BT address.
     * For Wi-Fi Direct: connects to the peer via a TCP stream over the P2P group.
     *
     * This is a suspending call that blocks until the connection is established
     * or fails. After success, the returned [TransportChannel] can be used
     * immediately to send and receive raw bytes.
     *
     * @param peer A [PeerDevice] obtained from a [TransportEvent.PeerDiscovered] event.
     */
    suspend fun connect(peer: PeerDevice): AppResult<TransportChannel>

    /**
     * Returns a [Flow] that emits a new [TransportChannel] each time a remote
     * peer successfully connects to this device.
     *
     * For Bluetooth: starts an RFCOMM server socket and accepts connections.
     * This flow must be collected continuously while the app is foreground.
     */
    fun listen(): Flow<AppResult<TransportChannel>>

    /**
     * Stops all transport activity: advertising, scanning, open channels, and
     * the listener socket. Must be called when the app enters background or is
     * destroyed.
     */
    suspend fun shutdown()
}
