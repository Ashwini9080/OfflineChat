package com.offlinechat.storage.repository

import com.offlinechat.core.model.Message
import com.offlinechat.core.model.MessageContent
import com.offlinechat.core.model.MessageStatus
import com.offlinechat.storage.dao.MessageDao
import com.offlinechat.storage.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository mediating between domain [Message] objects and SQLite [MessageEntity] records.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
) {

    fun observeMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getByConversationId(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getMessageById(id: String): Message? {
        return messageDao.getById(id)?.toDomain()
    }

    suspend fun saveMessage(message: Message, envelopeId: String? = null) {
        val entity = message.toEntity(envelopeId)
        messageDao.upsert(entity)
    }

    suspend fun updateMessageStatus(id: String, status: MessageStatus, deliveredAt: Long? = null) {
        val existing = messageDao.getById(id) ?: return
        val updated = existing.copy(
            status = status.name,
            deliveredAt = deliveredAt ?: existing.deliveredAt,
        )
        messageDao.update(updated)
    }

    suspend fun envelopeExists(envelopeId: String): Boolean {
        return messageDao.envelopeExists(envelopeId) > 0
    }

    private fun MessageEntity.toDomain(): Message {
        val content = when (contentType) {
            "TEXT" -> {
                // Decode UTF-8 string from Base64 stored body
                val bodyText = try {
                    String(Base64.getDecoder().decode(encryptedBody), Charsets.UTF_8)
                } catch (e: Exception) {
                    encryptedBody
                }
                MessageContent.Text(bodyText)
            }
            else -> MessageContent.Text(encryptedBody)
        }

        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = recipientId,
            content = content,
            sentAt = sentAt,
            deliveredAt = deliveredAt,
            readAt = readAt,
            status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.PENDING),
            isOutbound = isOutbound,
        )
    }

    private fun Message.toEntity(envelopeId: String?): MessageEntity {
        val (typeStr, bodyPayload) = when (val c = content) {
            is MessageContent.Text -> "TEXT" to Base64.getEncoder().encodeToString(c.body.toByteArray(Charsets.UTF_8))
            is MessageContent.File -> "FILE" to c.name
        }

        return MessageEntity(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = recipientId,
            contentType = typeStr,
            encryptedBody = bodyPayload,
            status = status.name,
            sentAt = sentAt,
            deliveredAt = deliveredAt,
            readAt = readAt,
            isOutbound = isOutbound,
            envelopeId = envelopeId ?: id,
        )
    }
}
