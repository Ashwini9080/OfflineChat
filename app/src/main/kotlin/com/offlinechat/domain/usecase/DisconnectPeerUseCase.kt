package com.offlinechat.domain.usecase

import com.offlinechat.domain.connection.ConnectionManager
import com.offlinechat.domain.repository.PeerRepository
import javax.inject.Inject

class DisconnectPeerUseCase @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val peerRepository: PeerRepository
) {
    suspend operator fun invoke(peerId: String) {
        connectionManager.disconnect(peerId)
        peerRepository.getPeerById(peerId)?.let { peer ->
            peerRepository.saveOrUpdatePeer(peer.copy(isConnected = false))
        }
    }
}
