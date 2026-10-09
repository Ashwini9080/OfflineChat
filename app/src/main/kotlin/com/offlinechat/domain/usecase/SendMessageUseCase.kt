package com.offlinechat.domain.usecase

import com.offlinechat.data.transport.MessageSyncEngine
import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.EncryptedMessagePayload
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.model.PeerTrustState
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import com.offlinechat.security.MessageSecurity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID
import javax.inject.Inject

/**
 * Domain use case responsible for the controlled outgoing message lifecycle:
 * 1. Validates peer trust state (blocks transmission if REVOKED).
 * 2. Persists locally as PENDING immediately for sender's view.
 * 3. Encrypts plaintext using AES-256-GCM AEAD and signs with Android Keystore private key.
 * 4. Places ONLY ciphertext, nonce, and signature into the transport envelope (zero plaintext over Bluetooth).
 * 5. Transitions state to SENDING -> SENT upon socket flush and registers ACK timeout watcher.
 * 6. Supports controlled retries reusing the original messageId.
 */
class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val transport: MessageTransport,
    private val syncEngine: MessageSyncEngine,
    private val messageSecurity: MessageSecurity
) {
    private val json = Json { ignoreUnknownKeys = true }

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

        // 1. Verify peer trust state
        val peerTrust = messageSecurity.getPeerTrustState(receiverId)
        if (peerTrust == PeerTrustState.REVOKED) {
            return Result.failure(SecurityException("Transmission blocked: Peer cryptographic identity is REVOKED."))
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

        // 2. Save locally with PENDING status for local conversation history
        messageRepository.saveMessage(message)
        conversationRepository.updateLastMessage(conversationId, trimmed, now)

        // 3. Transition to SENDING
        messageRepository.updateMessageStatus(messageId, MessageStatus.SENDING)

        // 4. Encrypt plaintext payload via MessageSecurity
        val wirePayload: ByteArray
        var nonceBytes: ByteArray? = null
        var signatureBytes: ByteArray? = null

        if (!messageSecurity.hasEstablishedSession(receiverId)) {
            syncEngine.sendHandshake(receiverId)
            withTimeoutOrNull(1500) {
                while (!messageSecurity.hasEstablishedSession(receiverId)) {
                    delay(100)
                }
            }
        }

        try {
            val encryptedPayload = messageSecurity.encryptMessage(
                plaintext = trimmed.toByteArray(Charsets.UTF_8),
                recipientPeerId = receiverId,
                messageId = messageId
            )
            val jsonPayload = json.encodeToString(EncryptedMessagePayload.serializer(), encryptedPayload)
            wirePayload = jsonPayload.toByteArray(Charsets.UTF_8)
            nonceBytes = Base64.getDecoder().decode(encryptedPayload.nonceBase64)
            signatureBytes = Base64.getDecoder().decode(encryptedPayload.signatureBase64)
        } catch (e: Exception) {
            // Keep status as PENDING so syncEngine will automatically flush when handshake arrives
            messageRepository.updateMessageStatus(messageId, MessageStatus.PENDING)
            return Result.failure(SecurityException("Encryption session pending: ${e.message}", e))
        }

        // 5. Prepare transport envelope carrying ONLY encrypted payload
        val envelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = messageId,
            conversationId = conversationId,
            senderId = senderId,
            receiverId = receiverId,
            timestamp = now,
            messageType = "TEXT",
            payload = wirePayload,
            nonce = nonceBytes,
            signature = signatureBytes
        )

        // 6. Transmit encrypted frame through transport
        val sendResult = transport.sendEnvelope(envelope)

        val updatedStatus = if (sendResult.isSuccess) {
            MessageStatus.SENT
        } else {
            MessageStatus.FAILED
        }

        // 7. Update status in local database
        messageRepository.updateMessageStatus(messageId, updatedStatus)

        return if (sendResult.isSuccess) {
            // 8. Watch for delivery ACK with timeout
            syncEngine.watchAckTimeout(messageId)
            Result.success(message.copy(status = MessageStatus.SENT))
        } else {
            Result.failure(sendResult.exceptionOrNull() ?: Exception("Transport delivery failed"))
        }
    }

    /**
     * Retries a failed outgoing message reusing the exact original messageId.
     * Re-encrypts with a fresh nonce to prevent IV reuse.
     */
    suspend fun retry(message: Message): Result<Message> {
        val peerTrust = messageSecurity.getPeerTrustState(message.receiverId)
        if (peerTrust == PeerTrustState.REVOKED) {
            return Result.failure(SecurityException("Transmission blocked: Peer cryptographic identity is REVOKED."))
        }

        // 1. Transition to SENDING
        messageRepository.updateMessageStatus(message.id, MessageStatus.SENDING)

        // 2. Re-encrypt with fresh nonce
        val wirePayload: ByteArray
        var nonceBytes: ByteArray? = null
        var signatureBytes: ByteArray? = null

        try {
            val encryptedPayload = messageSecurity.encryptMessage(
                plaintext = message.text.toByteArray(Charsets.UTF_8),
                recipientPeerId = message.receiverId,
                messageId = message.id
            )
            val jsonPayload = json.encodeToString(EncryptedMessagePayload.serializer(), encryptedPayload)
            wirePayload = jsonPayload.toByteArray(Charsets.UTF_8)
            nonceBytes = Base64.getDecoder().decode(encryptedPayload.nonceBase64)
            signatureBytes = Base64.getDecoder().decode(encryptedPayload.signatureBase64)
        } catch (e: Exception) {
            messageRepository.updateMessageStatus(message.id, MessageStatus.FAILED)
            return Result.failure(SecurityException("Retry encryption failed: ${e.message}", e))
        }

        val envelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            receiverId = message.receiverId,
            timestamp = message.timestamp,
            messageType = "TEXT",
            payload = wirePayload,
            nonce = nonceBytes,
            signature = signatureBytes
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
