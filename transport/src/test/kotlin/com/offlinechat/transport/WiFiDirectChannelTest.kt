package com.offlinechat.transport

import com.offlinechat.core.result.AppResult
import com.offlinechat.transport.wifi.WiFiDirectChannel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import javax.crypto.spec.SecretKeySpec

class WiFiDirectChannelTest {

    private val peerId = "peer_test_123"
    private val dummySessionKey = SecretKeySpec(ByteArray(32) { 0x42 }, "AES")

    @Test
    fun testSendWritesFramedData() = runBlocking {
        val outStream = ByteArrayOutputStream()
        val mockSocket = mockk<Socket>(relaxed = true) {
            every { isConnected } returns true
            every { isClosed } returns false
            every { getOutputStream() } returns outStream
        }

        val channel = WiFiDirectChannel(mockSocket, peerId, dummySessionKey)
        assertTrue(channel.isConnected)

        val testPayload = "Hello Wi-Fi Direct".toByteArray(Charsets.UTF_8)
        val sendResult = channel.send(testPayload)

        assertTrue(sendResult is AppResult.Success)
        val writtenBytes = outStream.toByteArray()
        val length = ByteBuffer.wrap(writtenBytes.copyOfRange(0, 4)).int
        assertEquals(testPayload.size, length)
        val body = writtenBytes.copyOfRange(4, writtenBytes.size)
        assertArrayEquals(testPayload, body)
    }

    @Test
    fun testReceiveReadsFramedData() = runBlocking {
        val testPayload = "Incoming frame test".toByteArray(Charsets.UTF_8)
        val inBytesStream = ByteArrayOutputStream()
        val dataOut = DataOutputStream(inBytesStream)
        dataOut.writeInt(testPayload.size)
        dataOut.write(testPayload)
        dataOut.flush()

        val mockSocket = mockk<Socket>(relaxed = true) {
            every { isConnected } returns true
            every { isClosed } returns false
            every { getInputStream() } returns ByteArrayInputStream(inBytesStream.toByteArray())
        }

        val channel = WiFiDirectChannel(mockSocket, peerId, dummySessionKey)
        val received = channel.receive().first()

        assertArrayEquals(testPayload, received)
    }

    @Test
    fun testDisconnectedSocketRejectsSend() = runBlocking {
        val mockSocket = mockk<Socket>(relaxed = true) {
            every { isConnected } returns false
            every { isClosed } returns true
        }

        val channel = WiFiDirectChannel(mockSocket, peerId, dummySessionKey)
        assertFalse(channel.isConnected)

        val sendResult = channel.send("Test".toByteArray(Charsets.UTF_8))
        assertTrue(sendResult is AppResult.Failure)
    }
}
