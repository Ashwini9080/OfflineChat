package com.offlinechat.transport.wifi

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
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

/**
 * A [TransportChannel] backed by a TCP socket established over a Wi-Fi Direct P2P group.
 *
 * Adheres to the exact same length-prefix framing protocol as [com.offlinechat.transport.bluetooth.RfcommChannel]:
 * ```
 * [ 4 bytes big-endian int: payload length ] [ N bytes: payload ]
 * ```
 */
class WiFiDirectChannel(
    private val socket: Socket,
    override val peerId: String,
    override val sessionKey: SecretKey? = null,
) : TransportChannel {

    companion object {
        const val MAX_FRAME_BYTES = 10 * 1024 * 1024 // 10 MB for Wi-Fi Direct
    }

    override val transportType: TransportType = TransportType.WIFI_DIRECT

    private val _isConnected = AtomicBoolean(socket.isConnected && !socket.isClosed)
    override val isConnected: Boolean get() = _isConnected.get() && socket.isConnected && !socket.isClosed

    private val outputStream by lazy { DataOutputStream(socket.getOutputStream()) }
    private val inputStream  by lazy { DataInputStream(socket.getInputStream()) }

    override suspend fun send(data: ByteArray): AppResult<Unit> = withContext(Dispatchers.IO) {
        if (!isConnected) {
            return@withContext AppResult.Failure(
                AppError.TransportSendFailed("Wi-Fi Direct TCP socket is not connected"),
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

    override fun receive(): Flow<ByteArray> = flow {
        try {
            while (isConnected) {
                val length = inputStream.readInt()
                if (length <= 0 || length > MAX_FRAME_BYTES) {
                    break
                }
                val buffer = ByteArray(length)
                inputStream.readFully(buffer)
                emit(buffer)
            }
        } catch (e: IOException) {
            _isConnected.set(false)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            _isConnected.set(false)
            runCatching { socket.close() }
        }
    }
}
