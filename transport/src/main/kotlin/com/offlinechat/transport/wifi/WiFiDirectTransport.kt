package com.offlinechat.transport.wifi

import com.offlinechat.core.model.DeviceIdentity
import com.offlinechat.core.model.TransportType
import com.offlinechat.core.result.AppError
import com.offlinechat.core.result.AppResult
import com.offlinechat.transport.api.PeerDevice
import com.offlinechat.transport.api.Transport
import com.offlinechat.transport.api.TransportChannel
import com.offlinechat.transport.api.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wi-Fi Direct (P2P) transport — Phase 3 placeholder.
 *
 * This class exists to:
 * 1. Reserve the [TransportType.WIFI_DIRECT] slot in [TransportManager].
 * 2. Provide a compile-time contract that the full implementation must satisfy.
 * 3. Serve as the starting point for the Phase 3 developer.
 *
 * ## Implementation guide (Phase 3)
 * Replace the stub bodies below with real implementations using:
 * - [android.net.wifi.p2p.WifiP2pManager] for group formation and peer discovery.
 * - [android.net.wifi.p2p.WifiP2pManager.DiscoverPeersListener] for scanning.
 * - A TCP [java.net.ServerSocket] / [java.net.Socket] over the P2P group interface
 *   for the data channel (same length-prefix framing as [bluetooth.RfcommChannel]).
 *
 * ## Why Wi-Fi Direct for Phase 3?
 * Wi-Fi Direct offers ~250 Mbps throughput vs Bluetooth Classic's ~3 Mbps,
 * making it the preferred transport for file transfers ([core.model.MessageContent.File]).
 * [TransportManager] will automatically prefer Wi-Fi Direct when available.
 *
 * @see <a href="https://developer.android.com/training/connect-devices-wirelessly/wifi-direct">
 *     Android Wi-Fi Direct guide</a>
 */
@Singleton
class WiFiDirectTransport @Inject constructor() : Transport {

    override val type: TransportType = TransportType.WIFI_DIRECT

    override fun events(): Flow<TransportEvent> = emptyFlow()

    override suspend fun startAdvertising(identity: DeviceIdentity): AppResult<Unit> =
        notImplemented()

    override suspend fun stopAdvertising() { /* no-op */ }

    override suspend fun startDiscovery(): AppResult<Unit> =
        notImplemented()

    override suspend fun stopDiscovery() { /* no-op */ }

    override suspend fun connect(peer: PeerDevice): AppResult<TransportChannel> =
        notImplemented()

    override fun listen(): Flow<AppResult<TransportChannel>> = emptyFlow()

    override suspend fun shutdown() { /* no-op */ }

    private fun <T> notImplemented(): AppResult<T> =
        AppResult.Failure(
            AppError.Unknown("Wi-Fi Direct transport is not yet implemented (Phase 3)"),
        )
}
