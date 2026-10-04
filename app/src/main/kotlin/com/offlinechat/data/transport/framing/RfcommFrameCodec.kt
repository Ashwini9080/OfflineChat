package com.offlinechat.data.transport.framing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Robust framing codec for Bluetooth RFCOMM byte streams.
 *
 * Framing Protocol:
 * [ 4-byte big-endian int: payload length ] [ N bytes: payload ]
 *
 * Guarantees:
 * - Length-prefix framing preserves message boundaries across byte stream fragmentation.
 * - [DataInputStream.readFully] handles partial reads, split packets, and multi-chunk deliveries.
 * - Rejects frames exceeding [MAX_FRAME_BYTES] to prevent uncontrolled memory allocation.
 * - Synchronised writes prevent race conditions when multiple coroutines transmit simultaneously.
 */
object RfcommFrameCodec {

    /** Maximum allowed size for a single message frame: 5 MB */
    const val MAX_FRAME_BYTES = 5 * 1024 * 1024

    /**
     * Encodes and writes a single length-prefixed frame to the output stream.
     * Synchronised on [outputStream] to guarantee serialized atomic frame transmission.
     */
    @Throws(IOException::class)
    fun writeFrame(outputStream: OutputStream, data: ByteArray) {
        if (data.size > MAX_FRAME_BYTES) {
            throw IOException("Frame size ${data.size} exceeds maximum limit of $MAX_FRAME_BYTES bytes")
        }
        val dataOut = DataOutputStream(outputStream)
        synchronized(outputStream) {
            dataOut.writeInt(data.size)
            dataOut.write(data)
            dataOut.flush()
        }
    }

    /**
     * Reads a single length-prefixed frame from the input stream.
     * Blocks on [DataInputStream] until a complete frame has been read or EOF is reached.
     *
     * @throws EOFException if the remote peer closed the socket.
     * @throws IOException if frame length is invalid or an IO failure occurs.
     */
    @Throws(IOException::class, EOFException::class)
    fun readFrame(inputStream: InputStream): ByteArray {
        val dataIn = if (inputStream is DataInputStream) inputStream else DataInputStream(inputStream)

        val length = dataIn.readInt()

        if (length <= 0) {
            throw IOException("Invalid frame length: $length (must be > 0)")
        }

        if (length > MAX_FRAME_BYTES) {
            throw IOException("Frame length $length exceeds maximum allowed limit of $MAX_FRAME_BYTES bytes")
        }

        val buffer = ByteArray(length)
        // readFully handles partial reads, loop iterations, and fragmented RFCOMM packets
        dataIn.readFully(buffer)
        return buffer
    }
}
