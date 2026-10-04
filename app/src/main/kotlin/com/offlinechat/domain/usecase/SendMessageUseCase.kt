package com.offlinechat.domain.usecase

import com.offlinechat.data.transport.MessageSyncEngine
import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import java.util.UUID
import javax.inject.Inject

/**
 * Domain use case responsible for the controlled outgoing message lifecycle:
 * 1. Persist locally as PENDING immediately.
 * 2. Transition state to SENDING as frame is handed to the transport.
 * 3. On successful transmission (socket flush), transition to SENT and launch ACK timeout watcher.
 * 4. On transport failure, transition to FAILED and allow controlled manual retry.
 * 5. Provides controlled retry reusing the exact messageId without creating duplicate entries.
 */
class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val transport: MessageTransport,
    private val syncEngine: MessageSyncEngine
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

        // 2. Transition to SENDING
        messageRepository.updateMessageStatus(messageId, MessageStatus.SENDING)

        // 3. Prepare envelope
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

        // 4. Transmit through transport
        val sendResult = transport.sendEnvelope(envelope)

        val updatedStatus = if (sendResult.isSuccess) {
            MessageStatus.SENT
        } else {
            MessageStatus.FAILED
        }

        // 5. Update status in local database
        messageRepository.updateMessageStatus(messageId, updatedStatus)

        return if (sendResult.isSuccess) {
            // 6. Watch for delivery ACK with timeout
            syncEngine.watchAckTimeout(messageId)
            Result.success(message.copy(status = MessageStatus.SENT))
        } else {
            Result.failure(sendResult.exceptionOrNull() ?: Exception("Transport delivery failed"))
        }
    }

    /**
     * Retries a failed outgoing message reusing the exact original messageId.
     * Prevents database duplication and respects the state transition model.
     */
    suspend fun retry(message: Message): Result<Message> {
        // 1. Transition to SENDING
        messageRepository.updateMessageStatus(message.id, MessageStatus.SENDING)

        val envelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            receiverId = message.receiverId,
            timestamp = message.timestamp,
            messageType = "TEXT",
            payload = message.text.toByteArray(Charsets.UTF_8)
        )

        val sendResult = transport.sendEnvelope(envelope)
        val updatedStatus = if (sendResult.isSuccess) {
            MessageStatus.SENT
        } else {
            MessageStatus.FAILED
        }

        messageRepository.updateMessageStatus(message.id, updatedStatus)

        return if (sendResult.isSuccess) {
            syncEngine.watchAckTimeout(message.id)
            Result.success(message.copy(status = MessageStatus.SENT))
        } else {
            Result.failure(sendResult.exceptionOrNull() ?: Exception("Transport retry delivery failed"))
        }
    }
}
