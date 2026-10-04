package com.offlinechat.storage.repository

import com.offlinechat.core.model.Conversation
import com.offlinechat.core.model.ConversationType
import com.offlinechat.storage.dao.ConversationDao
import com.offlinechat.storage.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllSortedByActivity().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getConversationById(id: String): Conversation? {
        return conversationDao.getById(id)?.toDomain()
    }

    suspend fun getOrCreateDirectConversation(localDeviceId: String, peerId: String): Conversation {
        val conversationId = deriveConversationId(localDeviceId, peerId)
        val existing = conversationDao.getById(conversationId)
        if (existing != null) {
            return existing.toDomain()
        }

        val participants = listOf(localDeviceId, peerId)
        val newEntity = ConversationEntity(
            id = conversationId,
            type = ConversationType.DIRECT.name,
            participantIds = json.encodeToString(participants),
            displayName = null,
            lastMessageId = null,
            lastActivityAt = System.currentTimeMillis(),
            unreadCount = 0,
        )
        conversationDao.insert(newEntity)
        return newEntity.toDomain()
    }

    suspend fun updateLastMessage(conversationId: String, messageId: String, timestamp: Long) {
        conversationDao.updateLastMessage(conversationId, messageId, timestamp)
    }

    suspend fun clearUnreadCount(conversationId: String) {
        conversationDao.clearUnreadCount(conversationId)
    }

    suspend fun incrementUnreadCount(conversationId: String) {
        conversationDao.incrementUnreadCount(conversationId)
    }

    private fun deriveConversationId(idA: String, idB: String): String {
        val (smaller, larger) = if (idA < idB) idA to idB else idB to idA
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$smaller:$larger".toByteArray(Charsets.UTF_8))
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun ConversationEntity.toDomain(): Conversation {
        val participants: List<String> = try {
            json.decodeFromString(participantIds)
        } catch (e: Exception) {
            emptyList()
        }

        return Conversation(
            id = id,
            type = runCatching { ConversationType.valueOf(type) }.getOrDefault(ConversationType.DIRECT),
            participantIds = participants,
            displayName = displayName,
            lastMessageId = lastMessageId,
            lastActivityAt = lastActivityAt,
            unreadCount = unreadCount,
        )
    }
}
