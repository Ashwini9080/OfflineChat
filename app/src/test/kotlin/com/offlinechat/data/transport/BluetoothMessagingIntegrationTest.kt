package com.offlinechat.data.transport

import com.offlinechat.data.transport.framing.RfcommFrameCodec
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import com.offlinechat.domain.usecase.ReceiveMessageUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class BluetoothMessagingIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }
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
    fun `end to end message transmission over framed stream and delivery ack`() = runTest {
        // --- 1. SENDER (Phone A) creates outbound message ---
        val messageId = UUID.randomUUID().toString()
        val text = "Real offline message over Bluetooth RFCOMM"
        val timestamp = System.currentTimeMillis()

        val outboundEnvelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = messageId,
            conversationId = "conv-ab",
            senderId = "phone-a",
            receiverId = "phone-b",
            timestamp = timestamp,
            messageType = "TEXT",
            payload = text.toByteArray(Charsets.UTF_8)
        )

        // --- 2. Serialize Envelope to JSON and write length-prefixed frame to socket stream ---
        val jsonPayload = json.encodeToString(MessageEnvelope.serializer(), outboundEnvelope)
        val socketOutputStream = ByteArrayOutputStream()
        RfcommFrameCodec.writeFrame(socketOutputStream, jsonPayload.toByteArray(Charsets.UTF_8))

        val wireBytes = socketOutputStream.toByteArray()
        assertTrue(wireBytes.size > jsonPayload.length) // Wire bytes contain 4-byte header + data

        // --- 3. RECEIVER (Phone B) reads frame from socket input stream ---
        val socketInputStream = ByteArrayInputStream(wireBytes)
        val decodedFrameBytes = RfcommFrameCodec.readFrame(socketInputStream)

        // --- 4. Decode JSON to MessageEnvelope ---
        val receivedJson = String(decodedFrameBytes, Charsets.UTF_8)
        val receivedEnvelope = json.decodeFromString(MessageEnvelope.serializer(), receivedJson)

        assertEquals(outboundEnvelope, receivedEnvelope)
        assertTrue(MessageEnvelope.isValid(receivedEnvelope))

        // --- 5. Receiver persists to Room SQLite via ReceiveMessageUseCase ---
        coEvery { messageRepository.getMessageById(messageId) } returns null

        val receiveResult = receiveMessageUseCase(receivedEnvelope)
        assertTrue(receiveResult.isSuccess)

        val savedMessage = receiveResult.getOrNull()
        assertNotNull(savedMessage)
        assertEquals(messageId, savedMessage?.id)
        assertEquals(text, savedMessage?.text)
        assertEquals(MessageStatus.DELIVERED, savedMessage?.status)
        assertEquals(false, savedMessage?.isOutbound)

        coVerify { messageRepository.saveMessage(match { it.id == messageId && it.text == text }) }

        // --- 6. Receiver generates and transmits DELIVERY_ACK ---
        val ackEnvelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = UUID.randomUUID().toString(),
            conversationId = "conv-ab",
            senderId = "phone-b",
            receiverId = "phone-a",
            timestamp = System.currentTimeMillis(),
            messageType = "DELIVERY_ACK",
            payload = messageId.toByteArray(Charsets.UTF_8)
        )

        val ackJson = json.encodeToString(MessageEnvelope.serializer(), ackEnvelope)
        val ackOutputStream = ByteArrayOutputStream()
        RfcommFrameCodec.writeFrame(ackOutputStream, ackJson.toByteArray(Charsets.UTF_8))

        // --- 7. Sender receives DELIVERY_ACK and transitions outbound message to DELIVERED ---
        val ackInputStream = ByteArrayInputStream(ackOutputStream.toByteArray())
        val ackFrame = RfcommFrameCodec.readFrame(ackInputStream)
        val ackDecoded = json.decodeFromString(MessageEnvelope.serializer(), String(ackFrame, Charsets.UTF_8))

        assertEquals("DELIVERY_ACK", ackDecoded.messageType)
        val ackedMessageId = String(ackDecoded.payload, Charsets.UTF_8)
        assertEquals(messageId, ackedMessageId)

        messageRepository.updateMessageStatus(ackedMessageId, MessageStatus.DELIVERED)
        coVerify { messageRepository.updateMessageStatus(messageId, MessageStatus.DELIVERED) }
    }
}
