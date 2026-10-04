package com.offlinechat.domain.usecase

import com.offlinechat.domain.model.EncryptedMessagePayload
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import com.offlinechat.security.MessageSecurity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class ReceiveMessageUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var messageSecurity: MessageSecurity
    private lateinit var receiveMessageUseCase: ReceiveMessageUseCase

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        messageRepository = mockk(relaxed = true)
        conversationRepository = mockk(relaxed = true)
        messageSecurity = mockk(relaxed = true)
        receiveMessageUseCase = ReceiveMessageUseCase(
            messageRepository = messageRepository,
            conversationRepository = conversationRepository,
            messageSecurity = messageSecurity
        )
    }

    @Test
    fun `valid incoming encrypted text envelope is decrypted, saved and returns message`() = runTest {
        val messageId = UUID.randomUUID().toString()
        val plainText = "Hello Phone A! End-to-end encrypted!"
        val encryptedPayload = EncryptedMessagePayload(
            sessionId = "session-test",
            nonceBase64 = "AAAA",
            ciphertextBase64 = "BBBB",
            signatureBase64 = "CCCC",
            senderFingerprint = "DDDD"
        )
        val payloadJson = json.encodeToString(EncryptedMessagePayload.serializer(), encryptedPayload)

        val envelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = messageId,
            conversationId = "conv-1",
            senderId = "phone-b",
            receiverId = "phone-a",
            timestamp = System.currentTimeMillis(),
            messageType = "TEXT",
            payload = payloadJson.toByteArray(Charsets.UTF_8)
        )

        coEvery { messageRepository.getMessageById(messageId) } returns null
        coEvery {
            messageSecurity.decryptMessage(
                encryptedPayload = any(),
                senderPeerId = "phone-b",
                messageId = messageId
            )
        } returns plainText.toByteArray(Charsets.UTF_8)

        val result = receiveMessageUseCase(envelope)

        assertTrue(result.isSuccess)
        val message = result.getOrNull()
        assertNotNull(message)
        assertEquals(messageId, message?.id)
        assertEquals(plainText, message?.text)
        assertEquals(MessageStatus.DELIVERED, message?.status)
        assertEquals(false, message?.isOutbound)

        coVerify { messageRepository.saveMessage(match { it.id == messageId && it.text == plainText }) }
        coVerify { conversationRepository.updateLastMessage("conv-1", plainText, any()) }
    }

    @Test
    fun `duplicate message is ignored and not re-inserted`() = runTest {
        val messageId = "duplicate-msg-id"
        val envelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = messageId,
            conversationId = "conv-1",
            senderId = "phone-b",
            receiverId = "phone-a",
            timestamp = System.currentTimeMillis(),
            messageType = "TEXT",
            payload = "Duplicate text".toByteArray(Charsets.UTF_8)
        )

        // Simulate message already existing in Room DB
        val existingMessage = Message(
            id = messageId,
            conversationId = "conv-1",
            senderId = "phone-b",
            receiverId = "phone-a",
            text = "Duplicate text",
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.DELIVERED,
            isOutbound = false
        )
        coEvery { messageRepository.getMessageById(messageId) } returns existingMessage

        val result = receiveMessageUseCase(envelope)

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull()) // Null indicates duplicate safely skipped

        // Verify saveMessage was NOT called
        coVerify(exactly = 0) { messageRepository.saveMessage(any()) }
    }

    @Test
    fun `replayed message outside allowable clock skew window is rejected`() = runTest {
        val messageId = "stale-replayed-msg"
        val staleTimestamp = System.currentTimeMillis() - (30 * 60 * 1000L) // 30 minutes old (exceeds 15m window)

        val envelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = messageId,
            conversationId = "conv-1",
            senderId = "phone-b",
            receiverId = "phone-a",
            timestamp = staleTimestamp,
            messageType = "TEXT",
            payload = "Old message".toByteArray(Charsets.UTF_8)
        )

        coEvery { messageRepository.getMessageById(messageId) } returns null

        val result = receiveMessageUseCase(envelope)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        coVerify(exactly = 0) { messageRepository.saveMessage(any()) }
    }

    @Test
    fun `corrupted ciphertext decryption failure rejects message and does not persist to database`() = runTest {
        val messageId = UUID.randomUUID().toString()
        val encryptedPayload = EncryptedMessagePayload(
            sessionId = "session-test",
            nonceBase64 = "AAAA",
            ciphertextBase64 = "TAMPERED_CIPHERTEXT",
            signatureBase64 = "CCCC",
            senderFingerprint = "DDDD"
        )
        val payloadJson = json.encodeToString(EncryptedMessagePayload.serializer(), encryptedPayload)

        val envelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = messageId,
            conversationId = "conv-1",
            senderId = "phone-b",
            receiverId = "phone-a",
            timestamp = System.currentTimeMillis(),
            messageType = "TEXT",
            payload = payloadJson.toByteArray(Charsets.UTF_8)
        )

        coEvery { messageRepository.getMessageById(messageId) } returns null
        coEvery {
            messageSecurity.decryptMessage(any(), any(), any())
        } throws SecurityException("AEAD authentication tag verification failed")

        val result = receiveMessageUseCase(envelope)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        coVerify(exactly = 0) { messageRepository.saveMessage(any()) }
    }

    @Test
    fun `malformed envelope is rejected with failure`() = runTest {
        val badEnvelope = MessageEnvelope(
            protocolVersion = 999, // Invalid protocol version
            messageId = "",
            conversationId = "conv-1",
            senderId = "",
            receiverId = "",
            timestamp = 0L,
            messageType = "TEXT",
            payload = ByteArray(0)
        )

        val result = receiveMessageUseCase(badEnvelope)
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { messageRepository.saveMessage(any()) }
    }
}
