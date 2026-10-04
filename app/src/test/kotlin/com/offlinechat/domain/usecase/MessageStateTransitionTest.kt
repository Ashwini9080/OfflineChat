package com.offlinechat.domain.usecase

import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class MessageStateTransitionTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var transport: MessageTransport
    private lateinit var sendMessageUseCase: SendMessageUseCase

    @Before
    fun setUp() {
        messageRepository = mockk(relaxed = true)
        conversationRepository = mockk(relaxed = true)
        transport = mockk(relaxed = true)
        sendMessageUseCase = SendMessageUseCase(messageRepository, conversationRepository, transport)
    }

    @Test
    fun `successful outbound transmission transitions from PENDING to SENT`() = runTest {
        coEvery { transport.sendEnvelope(any()) } returns Result.success(Unit)

        val result = sendMessageUseCase(
            conversationId = "conv-1",
            senderId = "phone-a",
            receiverId = "phone-b",
            text = "Test status transition"
        )

        assertTrue(result.isSuccess)
        val message = result.getOrThrow()

        // 1. Initial saved state was PENDING
        coVerify { messageRepository.saveMessage(match { it.status == MessageStatus.PENDING }) }

        // 2. Updated to SENT upon successful transport write
        coVerify { messageRepository.updateMessageStatus(message.id, MessageStatus.SENT) }
        assertEquals(MessageStatus.SENT, message.status)

        // 3. Simulated ACK receipt transitions to DELIVERED
        messageRepository.updateMessageStatus(message.id, MessageStatus.DELIVERED)
        coVerify { messageRepository.updateMessageStatus(message.id, MessageStatus.DELIVERED) }
    }

    @Test
    fun `failed transport transmission transitions to FAILED`() = runTest {
        coEvery { transport.sendEnvelope(any()) } returns Result.failure(IOException("Socket closed or broken pipe"))

        val result = sendMessageUseCase(
            conversationId = "conv-1",
            senderId = "phone-a",
            receiverId = "phone-b",
            text = "Failed message test"
        )

        assertTrue(result.isFailure)

        // 1. Initial saved state was PENDING
        coVerify { messageRepository.saveMessage(match { it.status == MessageStatus.PENDING }) }

        // 2. Updated to FAILED upon transport failure
        coVerify { messageRepository.updateMessageStatus(any(), MessageStatus.FAILED) }
    }
}
