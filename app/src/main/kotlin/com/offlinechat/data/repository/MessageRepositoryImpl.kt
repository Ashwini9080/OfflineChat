package com.offlinechat.data.repository

import com.offlinechat.data.local.database.MessageDao
import com.offlinechat.data.local.database.MessageEntity
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao
) : MessageRepository {

    override fun getMessagesForConversation(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getMessageById(id: String): Message? {
        return messageDao.getMessageById(id)?.toDomain()
    }

    override suspend fun saveMessage(message: Message) {
        messageDao.upsertMessage(message.toEntity())
    }

    override suspend fun updateMessageStatus(id: String, status: MessageStatus) {
        messageDao.updateMessageStatus(id, status.name)
    }

    override suspend fun getPendingOutboundMessagesForPeer(receiverId: String): List<Message> {
        return messageDao.getPendingOutboundMessagesForPeer(receiverId).map { it.toDomain() }
    }

    override suspend fun deleteMessagesForConversation(conversationId: String) {
        messageDao.deleteMessagesForConversation(conversationId)
    }

    private fun MessageEntity.toDomain(): Message {
        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            receiverId = receiverId,
            text = text,
            timestamp = timestamp,
            status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.PENDING),
            isOutbound = isOutbound
        )
    }

    private fun Message.toEntity(): MessageEntity {
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            receiverId = receiverId,
            text = text,
            timestamp = timestamp,
            status = status.name,
            isOutbound = isOutbound
        )
    }
}
