package com.offlinechat.domain.usecase

import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import javax.inject.Inject

/**
 * Domain use case to validate, deduplicate, and persist incoming text messages from peers.
 */
class ReceiveMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository
) {
    suspend operator fun invoke(envelope: MessageEnvelope): Result<Message?> {
        // 1. Envelope validation
        if (!MessageEnvelope.isValid(envelope)) {
            return Result.failure(IllegalArgumentException("Invalid or malformed message envelope"))
        }

        // 2. Idempotency & duplicate message protection (Section 12)
        val existing = messageRepository.getMessageById(envelope.messageId)
        if (existing != null) {
            return Result.success(null) // Duplicate detected, safely ignore
        }

        val text = String(envelope.payload, Charsets.UTF_8)
        val conversationId = envelope.conversationId.ifEmpty { envelope.senderId }

        val incomingMessage = Message(
            id = envelope.messageId,
            conversationId = conversationId,
            senderId = envelope.senderId,
            receiverId = envelope.receiverId,
            text = text,
            timestamp = envelope.timestamp,
            status = MessageStatus.DELIVERED,
            isOutbound = false
        )

        // 3. Local persistence
        messageRepository.saveMessage(incomingMessage)
        conversationRepository.updateLastMessage(
            conversationId = conversationId,
            text = text,
            timestamp = envelope.timestamp
        )

        return Result.success(incomingMessage)
    }
}
