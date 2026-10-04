package com.offlinechat.domain.usecase

import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SendMessageUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var transport: MessageTransport
    private lateinit var syncEngine: com.offlinechat.data.transport.MessageSyncEngine
    private lateinit var useCase: SendMessageUseCase

    @Before
    fun setUp() {
        messageRepository = mockk(relaxed = true)
        conversationRepository = mockk(relaxed = true)
        transport = mockk(relaxed = true)
        syncEngine = mockk(relaxed = true)

        useCase = SendMessageUseCase(
            messageRepository = messageRepository,
            conversationRepository = conversationRepository,
            transport = transport,
            syncEngine = syncEngine
        )
    }

    @Test
    fun `invoke with empty text returns failure`() = runTest {
        val result = useCase("convo-1", "user-a", "user-b", "   ")
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { messageRepository.saveMessage(any()) }
    }

    @Test
    fun `invoke with valid text successfully sends and saves message`() = runTest {
        coEvery { transport.sendEnvelope(any()) } returns Result.success(Unit)

        val result = useCase("convo-1", "user-a", "user-b", "Hello offline peer!")

        assertTrue(result.isSuccess)
        val sentMessage = result.getOrThrow()
        assertEquals("Hello offline peer!", sentMessage.text)
        assertEquals(MessageStatus.SENT, sentMessage.status)

        // Verify initial save
        coVerify { messageRepository.saveMessage(match { it.text == "Hello offline peer!" }) }
        // Verify conversation update
        coVerify { conversationRepository.updateLastMessage("convo-1", "Hello offline peer!", any()) }
        // Verify status update to SENT
        coVerify { messageRepository.updateMessageStatus(sentMessage.id, MessageStatus.SENT) }
    }

    @Test
    fun `invoke with transport failure updates status to FAILED`() = runTest {
        coEvery { transport.sendEnvelope(any()) } returns Result.failure(Exception("Socket disconnected"))

        val result = useCase("convo-1", "user-a", "user-b", "Hello offline peer!")

        assertTrue(result.isFailure)
        val capturedStatus = slot<MessageStatus>()
        coVerify { messageRepository.updateMessageStatus(any(), capture(capturedStatus)) }
        assertEquals(MessageStatus.FAILED, capturedStatus.captured)
    }

    @Test
    fun `retry reuses original messageId without creating duplicate records`() = runTest {
        val originalMessage = Message(
            id = "original-msg-123",
            conversationId = "convo-1",
            senderId = "user-a",
            receiverId = "user-b",
            text = "Retrying this message",
            timestamp = 1700000000L,
            status = MessageStatus.FAILED,
            isOutbound = true
        )

        coEvery { transport.sendEnvelope(any()) } returns Result.success(Unit)

        val result = useCase.retry(originalMessage)

        assertTrue(result.isSuccess)
        val retriedMessage = result.getOrThrow()

        // 1. Same message ID reused
        assertEquals("original-msg-123", retriedMessage.id)
        assertEquals(MessageStatus.SENT, retriedMessage.status)

        // 2. Verified no new saveMessage call was made (prevents duplicates)
        coVerify(exactly = 0) { messageRepository.saveMessage(any()) }

        // 3. Status transitioned to SENDING then SENT
        coVerify { messageRepository.updateMessageStatus("original-msg-123", MessageStatus.SENDING) }
        coVerify { messageRepository.updateMessageStatus("original-msg-123", MessageStatus.SENT) }
        coVerify { syncEngine.watchAckTimeout("original-msg-123") }
    }
}
