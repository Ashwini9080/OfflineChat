package com.offlinechat.data.transport

import com.offlinechat.domain.model.HandshakePayload
import com.offlinechat.domain.model.MessageEnvelope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class HandshakeFramingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `handshake payload serializes and deserializes correctly`() {
        val payload = HandshakePayload(
            deviceId = "test_dev_123",
            displayName = "Alice's Device",
            publicKeyBase64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE12345",
            ephemeralPublicKeyBase64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE67890",
            signatureBase64 = "MEYCIQCfakeSig123",
            identityFingerprint = "AA:BB:CC:DD",
            timestamp = 1700000000L
        )

        val serialized = json.encodeToString(HandshakePayload.serializer(), payload)
        val deserialized = json.decodeFromString(HandshakePayload.serializer(), serialized)

        assertEquals("test_dev_123", deserialized.deviceId)
        assertEquals("Alice's Device", deserialized.displayName)
        assertEquals("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE12345", deserialized.publicKeyBase64)
        assertEquals("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE67890", deserialized.ephemeralPublicKeyBase64)
        assertEquals("MEYCIQCfakeSig123", deserialized.signatureBase64)
        assertEquals("AA:BB:CC:DD", deserialized.identityFingerprint)
        assertEquals(1700000000L, deserialized.timestamp)
    }

    @Test
    fun `length prefixed frame stream writes and reads envelope accurately`() {
        val envelope = MessageEnvelope(
            protocolVersion = 1,
            messageId = "msg_001",
            conversationId = "conv_100",
            senderId = "alice",
            receiverId = "bob",
            timestamp = 1700000500L,
            messageType = "TEXT",
            payload = "Hello over Bluetooth RFCOMM!".toByteArray(Charsets.UTF_8)
        )

        val jsonString = json.encodeToString(MessageEnvelope.serializer(), envelope)
        val bytes = jsonString.toByteArray(Charsets.UTF_8)

        // Write frame to stream
        val byteOut = ByteArrayOutputStream()
        val dataOut = DataOutputStream(byteOut)
        dataOut.writeInt(bytes.size)
        dataOut.write(bytes)
        dataOut.flush()

        val rawWireData = byteOut.toByteArray()
        assertTrue(rawWireData.size > 4)

        // Read frame from stream
        val dataIn = DataInputStream(ByteArrayInputStream(rawWireData))
        val readLength = dataIn.readInt()
        assertEquals(bytes.size, readLength)

        val readBuffer = ByteArray(readLength)
        dataIn.readFully(readBuffer)
        val decodedEnvelope = json.decodeFromString(MessageEnvelope.serializer(), String(readBuffer, Charsets.UTF_8))

        assertEquals("msg_001", decodedEnvelope.messageId)
        assertEquals("conv_100", decodedEnvelope.conversationId)
        assertEquals("alice", decodedEnvelope.senderId)
        assertEquals("bob", decodedEnvelope.receiverId)
        assertEquals("Hello over Bluetooth RFCOMM!", String(decodedEnvelope.payload, Charsets.UTF_8))
    }
}
