package com.offlinechat.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import com.offlinechat.core.model.TransportType
import com.offlinechat.core.result.AppError
import com.offlinechat.core.result.AppResult
import com.offlinechat.transport.api.TransportChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [TransportChannel] backed by a Bluetooth Classic RFCOMM socket.
 *
 * ## Framing protocol
 * Raw TCP/RFCOMM is a byte stream with no inherent message boundaries.
 * We use a simple length-prefix framing:
 * ```
 * [ 4 bytes big-endian int: payload length ] [ N bytes: payload ]
 * ```
 * [DataOutputStream.writeInt] and [DataInputStream.readInt] handle
 * endianness correctly. Maximum single-frame size is limited by [MAX_FRAME_BYTES].
 *
 * ## Thread safety
 * [send] uses [DataOutputStream] which is NOT thread-safe. The class
 * synchronises on [outputStream] so concurrent sends are serialised.
 * [receive] should only be collected from one coroutine at a time.
 *
 * @param socket     The already-connected [BluetoothSocket].
 * @param remotePeerId The [DeviceIdentity.id] of the remote device, resolved
 *                   during the RFCOMM handshake.
 */
@SuppressLint("MissingPermission")
class RfcommChannel(
    private val socket: BluetoothSocket,
    override val peerId: String,
    override val sessionKey: javax.crypto.SecretKey? = null,
) : TransportChannel {

    companion object {
        /** Hard cap on a single frame to prevent memory exhaustion attacks. */
        const val MAX_FRAME_BYTES = 5 * 1024 * 1024 // 5 MB
    }

    override val transportType: TransportType = TransportType.BLUETOOTH

    private val _isConnected = AtomicBoolean(socket.isConnected)
    override val isConnected: Boolean get() = _isConnected.get() && socket.isConnected

    private val outputStream by lazy { DataOutputStream(socket.outputStream) }
    private val inputStream  by lazy { DataInputStream(socket.inputStream) }

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Writes a length-prefixed frame to the RFCOMM socket.
     *
     * Synchronised so concurrent [send] calls are serialised without reordering
     * (important for message ordering guarantees at the application level).
     */
    override suspend fun send(data: ByteArray): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            if (!isConnected) {
                return@withContext AppResult.Failure(
                    AppError.TransportSendFailed("RFCOMM socket is not connected"),
                )
            }

            runCatching {
                synchronized(outputStream) {
                    outputStream.writeInt(data.size)
                    outputStream.write(data)
                    outputStream.flush()
                }
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = {
                    _isConnected.set(false)
                    AppResult.Failure(
                        AppError.TransportSendFailed(it.message ?: "Write failed"),
                        it,
                    )
                },
            )
        }

    // ── Receive ───────────────────────────────────────────────────────────────

    /**
     * Returns a [Flow] that reads length-prefixed frames from the socket and
     * emits each one as a [ByteArray].
     *
     * The flow uses [flowOn(Dispatchers.IO)] so reads never block the main thread.
     * The flow completes normally when the remote closes the socket, and
     * completes exceptionally if an [IOException] occurs.
     *
     * Callers should handle both: use [kotlinx.coroutines.flow.catch] to react
     * to errors, and launch re-connection via [TransportManager] on completion.
     */
    override fun receive(): Flow<ByteArray> = flow {
        try {
            while (isConnected) {
                val length = inputStream.readInt()

                if (length <= 0 || length > MAX_FRAME_BYTES) {
                    // Invalid frame header — close to prevent parsing loop
                    break
                }

                val buffer = ByteArray(length)
                inputStream.readFully(buffer)
                emit(buffer)
            }
        } catch (e: IOException) {
            // Socket closed by remote or error — let the flow complete
            _isConnected.set(false)
        }
    }.flowOn(Dispatchers.IO)

    // ── Close ─────────────────────────────────────────────────────────────────

    /** Closes the underlying [BluetoothSocket]. Idempotent. */
    override suspend fun close() {
        withContext(Dispatchers.IO) {
            _isConnected.set(false)
            runCatching { socket.close() }
        }
    }
}
