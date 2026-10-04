package com.offlinechat.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MessageEnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serialize and deserialize message envelope round trip`() {
        val original = MessageEnvelope(
            protocolVersion = 1,
            messageId = UUID.randomUUID().toString(),
            conversationId = "conv-123",
            senderId = "device-A",
            receiverId = "device-B",
            timestamp = 1728000000000L,
            messageType = "TEXT",
            payload = "Hello over Bluetooth!".toByteArray(Charsets.UTF_8)
        )

        val jsonString = json.encodeToString(MessageEnvelope.serializer(), original)
        val deserialized = json.decodeFromString(MessageEnvelope.serializer(), jsonString)

        assertEquals(original.protocolVersion, deserialized.protocolVersion)
        assertEquals(original.messageId, deserialized.messageId)
        assertEquals(original.conversationId, deserialized.conversationId)
        assertEquals(original.senderId, deserialized.senderId)
        assertEquals(original.receiverId, deserialized.receiverId)
        assertEquals(original.timestamp, deserialized.timestamp)
        assertEquals(original.messageType, deserialized.messageType)
        assertArrayEquals(original.payload, deserialized.payload)
        assertEquals(original, deserialized)
    }

    @Test
    fun `validate valid envelope returns true`() {
        val valid = MessageEnvelope(
            protocolVersion = 1,
            messageId = "msg-1",
            conversationId = "conv-1",
            senderId = "sender-1",
            receiverId = "receiver-1",
            timestamp = System.currentTimeMillis(),
            messageType = "TEXT",
            payload = "Valid Payload".toByteArray(Charsets.UTF_8)
        )
        assertTrue(MessageEnvelope.isValid(valid))
    }

    @Test
    fun `validate rejects incompatible protocol version`() {
        val badVersion = MessageEnvelope(
            protocolVersion = 99,
            messageId = "msg-1",
            conversationId = "conv-1",
            senderId = "sender-1",
            receiverId = "receiver-1",
            timestamp = System.currentTimeMillis(),
            messageType = "TEXT",
            payload = "Payload".toByteArray(Charsets.UTF_8)
        )
        assertFalse(MessageEnvelope.isValid(badVersion))
    }

    @Test
    fun `validate rejects blank messageId or missing endpoints`() {
        val blankId = MessageEnvelope(
            protocolVersion = 1,
            messageId = "   ",
            conversationId = "conv-1",
            senderId = "sender-1",
            receiverId = "receiver-1",
            timestamp = System.currentTimeMillis(),
            messageType = "TEXT",
            payload = "Payload".toByteArray(Charsets.UTF_8)
        )
        assertFalse(MessageEnvelope.isValid(blankId))

        val missingSender = blankId.copy(messageId = "valid", senderId = "")
        assertFalse(MessageEnvelope.isValid(missingSender))

        val missingReceiver = blankId.copy(messageId = "valid", receiverId = "")
        assertFalse(MessageEnvelope.isValid(missingReceiver))
    }

    @Test
    fun `validate rejects empty payload or unknown message type`() {
        val emptyPayload = MessageEnvelope(
            protocolVersion = 1,
            messageId = "msg-1",
            conversationId = "conv-1",
            senderId = "sender-1",
            receiverId = "receiver-1",
            timestamp = System.currentTimeMillis(),
            messageType = "TEXT",
            payload = ByteArray(0)
        )
        assertFalse(MessageEnvelope.isValid(emptyPayload))

        val unknownType = emptyPayload.copy(
            payload = "data".toByteArray(),
            messageType = "MALICIOUS_INJECTION"
        )
        assertFalse(MessageEnvelope.isValid(unknownType))
    }
}
