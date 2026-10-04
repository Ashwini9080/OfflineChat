package com.offlinechat.domain.usecase

import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import java.util.UUID
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val transport: MessageTransport
) {
    suspend operator fun invoke(
        conversationId: String,
        senderId: String,
        receiverId: String,
        text: String
    ): Result<Message> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty"))
        }

        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val message = Message(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            receiverId = receiverId,
            text = trimmed,
            timestamp = now,
            status = MessageStatus.PENDING,
            isOutbound = true
        )

        // 1. Save locally with PENDING status
        messageRepository.saveMessage(message)
        conversationRepository.updateLastMessage(conversationId, trimmed, now)

        // 2. Prepare envelope
        val envelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = messageId,
            conversationId = conversationId,
            senderId = senderId,
            receiverId = receiverId,
            timestamp = now,
            messageType = "TEXT",
            payload = trimmed.toByteArray(Charsets.UTF_8)
        )

        // 3. Transmit through transport
        val sendResult = transport.sendEnvelope(envelope)

        val updatedStatus = if (sendResult.isSuccess) {
            MessageStatus.SENT
        } else {
            MessageStatus.FAILED
        }

        // 4. Update status in local database
        messageRepository.updateMessageStatus(messageId, updatedStatus)

        return if (sendResult.isSuccess) {
            Result.success(message.copy(status = MessageStatus.SENT))
        } else {
            Result.failure(sendResult.exceptionOrNull() ?: Exception("Transport delivery failed"))
        }
    }
}
