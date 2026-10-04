package com.offlinechat.domain.usecase

import com.offlinechat.data.discovery.DeviceDiscovery
import com.offlinechat.domain.model.DiscoveryStatus
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.repository.PeerRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class DiscoverPeersUseCase @Inject constructor(
    private val deviceDiscovery: DeviceDiscovery,
    private val peerRepository: PeerRepository
) {
    val discoveredPeers: Flow<List<Peer>> = deviceDiscovery.discoveredPeers
    val isDiscovering: Flow<Boolean> = deviceDiscovery.isDiscovering
    val discoveryStatus: Flow<DiscoveryStatus> = deviceDiscovery.discoveryStatus

    suspend fun startDiscovery(localDisplayName: String, localDeviceId: String): Result<Unit> {
        return deviceDiscovery.startDiscovery(localDisplayName, localDeviceId)
    }

    suspend fun stopDiscovery() {
        deviceDiscovery.stopDiscovery()
    }

    suspend fun trustPeer(peer: Peer) {
        peerRepository.saveOrUpdatePeer(peer.copy(isTrusted = true))
    }
}
