package com.offlinechat.data.discovery

import com.offlinechat.domain.model.DiscoveryStatus
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import kotlinx.coroutines.flow.Flow

interface DeviceDiscovery {
    val transportType: TransportType

    /** Cold or Shared stream of currently discovered nearby peers */
    val discoveredPeers: Flow<List<Peer>>

    /** Observable flow of the discrete discovery lifecycle states */
    val discoveryStatus: Flow<DiscoveryStatus>

    /** Whether scanning/discovery is actively running */
    val isDiscovering: Flow<Boolean>

    /** Starts broadcasting presence and scanning for nearby peers */
    suspend fun startDiscovery(localDisplayName: String, localDeviceId: String): Result<Unit>

    /** Stops discovery and broadcasting */
    suspend fun stopDiscovery()
}
