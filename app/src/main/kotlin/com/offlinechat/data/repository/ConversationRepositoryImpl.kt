package com.offlinechat.data.repository

import com.offlinechat.data.local.database.ConversationDao
import com.offlinechat.data.local.database.ConversationEntity
import com.offlinechat.domain.model.Conversation
import com.offlinechat.domain.model.TransportType
import com.offlinechat.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao
) : ConversationRepository {

    override fun getConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getConversationById(id: String): Conversation? {
        return conversationDao.getConversationById(id)?.toDomain()
    }

    override suspend fun getOrCreateConversation(
        peerId: String,
        peerDisplayName: String,
        transportType: TransportType
    ): Conversation {
        val existing = conversationDao.getConversationByPeerId(peerId)
        if (existing != null) {
            return existing.toDomain()
        }

        val newEntity = ConversationEntity(
            id = UUID.randomUUID().toString(),
            peerId = peerId,
            peerDisplayName = peerDisplayName,
            lastMessage = "",
            lastActivityAt = System.currentTimeMillis(),
            unreadCount = 0,
            transportType = transportType.name
        )
        conversationDao.upsertConversation(newEntity)
        return newEntity.toDomain()
    }

    override suspend fun updateLastMessage(conversationId: String, text: String, timestamp: Long) {
        conversationDao.updateLastMessage(conversationId, text, timestamp)
    }

    override suspend fun markAsRead(conversationId: String) {
        conversationDao.markAsRead(conversationId)
    }

    override suspend fun deleteConversation(id: String) {
        conversationDao.deleteConversation(id)
    }

    private fun ConversationEntity.toDomain(): Conversation {
        return Conversation(
            id = id,
            peerId = peerId,
            peerDisplayName = peerDisplayName,
            lastMessage = lastMessage,
            lastActivityAt = lastActivityAt,
            unreadCount = unreadCount,
            transportType = runCatching { TransportType.valueOf(transportType) }.getOrDefault(TransportType.BLUETOOTH)
        )
    }
}
