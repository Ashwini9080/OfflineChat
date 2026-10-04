package com.offlinechat.domain.usecase

import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
    private lateinit var receiveMessageUseCase: ReceiveMessageUseCase

    @Before
    fun setUp() {
        messageRepository = mockk(relaxed = true)
        conversationRepository = mockk(relaxed = true)
        receiveMessageUseCase = ReceiveMessageUseCase(messageRepository, conversationRepository)
    }

    @Test
    fun `valid incoming text envelope is saved and returns message`() = runTest {
        val messageId = UUID.randomUUID().toString()
        val envelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = messageId,
            conversationId = "conv-1",
            senderId = "phone-b",
            receiverId = "phone-a",
            timestamp = System.currentTimeMillis(),
            messageType = "TEXT",
            payload = "Hello Phone A!".toByteArray(Charsets.UTF_8)
        )

        coEvery { messageRepository.getMessageById(messageId) } returns null

        val result = receiveMessageUseCase(envelope)

        assertTrue(result.isSuccess)
        val message = result.getOrNull()
        assertNotNull(message)
        assertEquals(messageId, message?.id)
        assertEquals("Hello Phone A!", message?.text)
        assertEquals(MessageStatus.DELIVERED, message?.status)
        assertEquals(false, message?.isOutbound)

        coVerify { messageRepository.saveMessage(match { it.id == messageId && it.text == "Hello Phone A!" }) }
        coVerify { conversationRepository.updateLastMessage("conv-1", "Hello Phone A!", any()) }
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
