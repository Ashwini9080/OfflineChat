package com.offlinechat.data.transport.framing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

class RfcommFrameCodecTest {

    @Test
    fun `encode and decode single message frame`() {
        val payload = "Hello, Offline World!".toByteArray(Charsets.UTF_8)
        val outStream = ByteArrayOutputStream()

        RfcommFrameCodec.writeFrame(outStream, payload)

        val inStream = ByteArrayInputStream(outStream.toByteArray())
        val decoded = RfcommFrameCodec.readFrame(inStream)

        assertArrayEquals(payload, decoded)
        assertEquals("Hello, Offline World!", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `handles multiple messages arriving sequentially in one stream`() {
        val messages = listOf(
            "Message 1",
            "Message 2: with more bytes",
            "Message 3: emojis 🚀📱✨",
            "Message 4: final"
        )

        val outStream = ByteArrayOutputStream()
        messages.forEach { msg ->
            RfcommFrameCodec.writeFrame(outStream, msg.toByteArray(Charsets.UTF_8))
        }

        val inStream = ByteArrayInputStream(outStream.toByteArray())

        val received = mutableListOf<String>()
        for (i in messages.indices) {
            val decoded = RfcommFrameCodec.readFrame(inStream)
            received.add(String(decoded, Charsets.UTF_8))
        }

        assertEquals(messages, received)
    }

    @Test
    fun `handles partial read and fragmented frame delivery`() {
        val payload = ByteArray(2048) { (it % 128).toByte() }
        val outStream = ByteArrayOutputStream()
        RfcommFrameCodec.writeFrame(outStream, payload)

        val rawBytes = outStream.toByteArray()

        // Custom InputStream simulating slow Bluetooth socket packet delivery (chunked into 64-byte reads)
        val chunkedInputStream = object : java.io.InputStream() {
            private var index = 0
            override fun read(): Int {
                if (index >= rawBytes.size) return -1
                return rawBytes[index++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= rawBytes.size) return -1
                val chunk = minOf(len, 64, rawBytes.size - index)
                System.arraycopy(rawBytes, index, b, off, chunk)
                index += chunk
                return chunk
            }
        }

        val decoded = RfcommFrameCodec.readFrame(chunkedInputStream)
        assertArrayEquals(payload, decoded)
    }

    @Test(expected = IOException::class)
    fun `rejects frame with negative or zero length`() {
        val badStream = ByteArrayOutputStream()
        val dataOut = DataOutputStream(badStream)
        dataOut.writeInt(0) // Invalid 0 length

        val inStream = ByteArrayInputStream(badStream.toByteArray())
        RfcommFrameCodec.readFrame(inStream)
    }

    @Test(expected = IOException::class)
    fun `rejects frame exceeding maximum allowed limit`() {
        val badStream = ByteArrayOutputStream()
        val dataOut = DataOutputStream(badStream)
        dataOut.writeInt(RfcommFrameCodec.MAX_FRAME_BYTES + 1024) // Exceeds 5MB limit

        val inStream = ByteArrayInputStream(badStream.toByteArray())
        RfcommFrameCodec.readFrame(inStream)
    }

    @Test(expected = EOFException::class)
    fun `throws EOFException when connection terminates prematurely`() {
        val partialStream = ByteArrayInputStream(byteArrayOf(0, 0, 0, 10, 1, 2)) // Header says 10 bytes, only 2 provided
        RfcommFrameCodec.readFrame(partialStream)
    }
}
