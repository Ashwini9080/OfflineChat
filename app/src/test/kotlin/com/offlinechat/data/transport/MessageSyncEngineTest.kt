package com.offlinechat.data.transport

import android.content.Context
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import com.offlinechat.domain.repository.PeerRepository
import com.offlinechat.domain.usecase.ReceiveMessageUseCase
import com.offlinechat.security.IdentityManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MessageSyncEngineTest {

    private val context = mockk<Context>(relaxed = true)
    private val transport = mockk<MessageTransport>(relaxed = true)
    private val receiveMessageUseCase = mockk<ReceiveMessageUseCase>(relaxed = true)
    private val messageRepository = mockk<MessageRepository>(relaxed = true)
    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val peerRepository = mockk<PeerRepository>(relaxed = true)
    private val identityManager = mockk<IdentityManager>(relaxed = true)

    private val incomingEnvelopes = MutableSharedFlow<MessageEnvelope>(extraBufferCapacity = 64)

    private lateinit var syncEngine: MessageSyncEngine

    @Before
    fun setUp() {
        every { transport.incomingEnvelopes } returns incomingEnvelopes
        every { identityManager.deviceId } returns "local_device_id"

        syncEngine = MessageSyncEngine(
            context = context,
            transport = transport,
            receiveMessageUseCase = receiveMessageUseCase,
            messageRepository = messageRepository,
            conversationRepository = conversationRepository,
            peerRepository = peerRepository,
            identityManager = identityManager
        )
    }

    @Test
    fun `incoming TEXT envelope saves message, increments unread count if inactive, and returns DELIVERY_ACK`() = runTest {
        syncEngine.start()
        syncEngine.setActiveConversation(null) // User is on Home screen

        val textEnvelope = MessageEnvelope(
            messageId = "msg_123",
            conversationId = "conv_456",
            senderId = "peer_bob",
            receiverId = "local_device_id",
            timestamp = 1700000000L,
            messageType = "TEXT",
            payload = "Hey Alice, this is offline!".toByteArray(Charsets.UTF_8)
        )

        val savedMsg = Message(
            id = "msg_123",
            conversationId = "conv_456",
            senderId = "peer_bob",
            receiverId = "local_device_id",
            text = "Hey Alice, this is offline!",
            timestamp = 1700000000L,
            status = MessageStatus.DELIVERED,
            isOutbound = false
        )
        coEvery { receiveMessageUseCase(textEnvelope) } returns Result.success(savedMsg)

        val ackEnvelopeSlot = slot<MessageEnvelope>()
        coEvery { transport.sendEnvelope(capture(ackEnvelopeSlot)) } returns Result.success(Unit)

        incomingEnvelopes.emit(textEnvelope)

        coVerify(timeout = 2000) { receiveMessageUseCase(textEnvelope) }
        coVerify(timeout = 2000) { conversationRepository.incrementUnreadCount("conv_456") }
        coVerify(timeout = 2000) { transport.sendEnvelope(any()) }

        assertEquals("DELIVERY_ACK", ackEnvelopeSlot.captured.messageType)
        assertEquals("peer_bob", ackEnvelopeSlot.captured.receiverId)
        assertEquals("msg_123", String(ackEnvelopeSlot.captured.payload, Charsets.UTF_8))
    }

    @Test
    fun `incoming TEXT envelope marks as read when conversation is actively open`() = runTest {
        syncEngine.start()
        syncEngine.setActiveConversation("conv_456") // User is inside this chat

        val textEnvelope = MessageEnvelope(
            messageId = "msg_123",
            conversationId = "conv_456",
            senderId = "peer_bob",
            receiverId = "local_device_id",
            timestamp = 1700000000L,
            messageType = "TEXT",
            payload = "Hey Alice!".toByteArray(Charsets.UTF_8)
        )

        val savedMsg = Message(
            id = "msg_123",
            conversationId = "conv_456",
            senderId = "peer_bob",
            receiverId = "local_device_id",
            text = "Hey Alice!",
            timestamp = 1700000000L,
            status = MessageStatus.DELIVERED,
            isOutbound = false
        )
        coEvery { receiveMessageUseCase(textEnvelope) } returns Result.success(savedMsg)
        coEvery { transport.sendEnvelope(any()) } returns Result.success(Unit)

        incomingEnvelopes.emit(textEnvelope)

        coVerify(timeout = 2000) { conversationRepository.markAsRead("conv_456") }
        coVerify(exactly = 0) { conversationRepository.incrementUnreadCount(any()) }
    }

    @Test
    fun `incoming DELIVERY_ACK envelope updates original message status to DELIVERED`() = runTest {
        syncEngine.start()

        val ackEnvelope = MessageEnvelope(
            messageId = "ack_789",
            conversationId = "conv_456",
            senderId = "peer_bob",
            receiverId = "local_device_id",
            timestamp = 1700000100L,
            messageType = "DELIVERY_ACK",
            payload = "original_msg_001".toByteArray(Charsets.UTF_8)
        )

        incomingEnvelopes.emit(ackEnvelope)

        coVerify(timeout = 2000) {
            messageRepository.updateMessageStatus("original_msg_001", MessageStatus.DELIVERED)
        }
    }

    @Test
    fun `watchAckTimeout transitions SENT message to FAILED if no ACK arrives within timeout`() = runTest {
        val testMessage = Message(
            id = "timeout_msg_01",
            conversationId = "conv_123",
            senderId = "local_device_id",
            receiverId = "peer_bob",
            text = "Hello?",
            timestamp = 1700000000L,
            status = MessageStatus.SENT,
            isOutbound = true
        )
        coEvery { messageRepository.getMessageById("timeout_msg_01") } returns testMessage

        syncEngine.watchAckTimeout("timeout_msg_01", timeoutMillis = 50L)

        // Wait for timeout to expire
        delay(100L)

        coVerify { messageRepository.updateMessageStatus("timeout_msg_01", MessageStatus.FAILED) }
    }

    @Test
    fun `flushing pending messages transitions to SENDING and SENT`() = runTest {
        val pendingMsg = Message(
            id = "pending_01",
            conversationId = "conv_123",
            senderId = "local_device_id",
            receiverId = "peer_bob",
            text = "Queued while offline",
            timestamp = 1700000000L,
            status = MessageStatus.PENDING,
            isOutbound = true
        )

        coEvery { messageRepository.getPendingOutboundMessagesForPeer("peer_bob") } returns listOf(pendingMsg)
        coEvery { transport.sendEnvelope(any()) } returns Result.success(Unit)

        syncEngine.flushPendingMessages("peer_bob")

        coVerify { messageRepository.updateMessageStatus("pending_01", MessageStatus.SENDING) }
        coVerify { transport.sendEnvelope(match { it.messageId == "pending_01" && it.messageType == "TEXT" }) }
        coVerify { messageRepository.updateMessageStatus("pending_01", MessageStatus.SENT) }
    }
}
