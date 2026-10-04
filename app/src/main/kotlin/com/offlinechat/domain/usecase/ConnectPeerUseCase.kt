package com.offlinechat.domain.usecase

import com.offlinechat.domain.connection.ConnectionManager
import com.offlinechat.domain.model.Conversation
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.PeerRepository
import javax.inject.Inject

class ConnectPeerUseCase @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val conversationRepository: ConversationRepository,
    private val peerRepository: PeerRepository
) {
    suspend operator fun invoke(peer: Peer): Result<Conversation> {
        val connectResult = connectionManager.connect(peer)
        if (connectResult.isFailure) {
            return Result.failure(connectResult.exceptionOrNull() ?: Exception("Failed to connect"))
        }

        // Save peer as known
        peerRepository.saveOrUpdatePeer(peer.copy(isConnected = true))

        // Get or create direct conversation
        val conversation = conversationRepository.getOrCreateConversation(
            peerId = peer.deviceId,
            peerDisplayName = peer.displayName,
            transportType = peer.transportType
        )

        return Result.success(conversation)
    }
}
