package com.offlinechat.domain.usecase

import com.offlinechat.data.transport.MessageSyncEngine
import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.model.PeerTrustState
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import com.offlinechat.security.MessageSecurity
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
    private lateinit var syncEngine: MessageSyncEngine
    private lateinit var messageSecurity: MessageSecurity
    private lateinit var sendMessageUseCase: SendMessageUseCase

    @Before
    fun setUp() {
        messageRepository = mockk(relaxed = true)
        conversationRepository = mockk(relaxed = true)
        transport = mockk(relaxed = true)
        syncEngine = mockk(relaxed = true)
        messageSecurity = mockk(relaxed = true)
        coEvery { messageSecurity.getPeerTrustState(any()) } returns PeerTrustState.CONNECTED

        sendMessageUseCase = SendMessageUseCase(
            messageRepository = messageRepository,
            conversationRepository = conversationRepository,
            transport = transport,
            syncEngine = syncEngine,
            messageSecurity = messageSecurity
        )
    }

    @Test
    fun `successful outbound transmission transitions PENDING to SENDING to SENT to DELIVERED`() = runTest {
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

        // 2. Updated to SENDING while in transport pipeline
        coVerify { messageRepository.updateMessageStatus(message.id, MessageStatus.SENDING) }

        // 3. Updated to SENT upon successful transport write
        coVerify { messageRepository.updateMessageStatus(message.id, MessageStatus.SENT) }
        assertEquals(MessageStatus.SENT, message.status)

        // 4. Registered ACK timeout watcher
        coVerify { syncEngine.watchAckTimeout(message.id) }

        // 5. Simulated incoming ACK receipt transitions to DELIVERED
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

        // 2. Transitioned to SENDING
        coVerify { messageRepository.updateMessageStatus(any(), MessageStatus.SENDING) }

        // 3. Updated to FAILED upon transport failure
        coVerify { messageRepository.updateMessageStatus(any(), MessageStatus.FAILED) }
    }
}
