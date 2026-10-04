package com.offlinechat.transport.api

import com.offlinechat.core.model.TransportType
import com.offlinechat.core.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Represents an open, bidirectional data channel to a single remote peer.
 *
 * ## Lifecycle
 * A [TransportChannel] is created by [Transport.connect] (outbound) or emitted
 * from [Transport.listen] (inbound). Once created, it is in "connected" state.
 * Call [close] explicitly when done — this drains in-flight data and closes
 * the underlying socket gracefully.
 *
 * ## Thread safety
 * [send] and [receive] can be called concurrently from different coroutines.
 * Implementations must use their own synchronisation internally.
 *
 * ## Error handling
 * If the underlying socket drops, [receive] will terminate its [Flow] with a
 * [kotlinx.coroutines.flow.FlowCollector.emit] of the last [ByteArray] and then
 * complete. The caller is expected to handle re-connection via [TransportManager].
 */
interface TransportChannel {

    /** The remote peer's [DeviceIdentity.id]. */
    val peerId: String

    /** Which transport mechanism backs this channel. */
    val transportType: TransportType

    /** Whether the underlying socket is still connected. */
    val isConnected: Boolean

    /**
     * Sends raw [data] bytes to the peer.
     *
     * This is a suspending call that completes when the data has been handed to
     * the OS socket buffer (not necessarily received by the peer). For reliability,
     * the :messaging layer requires an ACK from the receiver.
     *
     * @return [AppResult.Failure] with [AppError.TransportSendFailed] if the
     *         socket write fails.
     */
    suspend fun send(data: ByteArray): AppResult<Unit>

    /**
     * A cold [Flow] that emits each incoming [ByteArray] frame as received.
     *
     * The flow is "framed" — each emission represents exactly one logical message
     * (length-prefixed by the implementation) rather than a raw TCP fragment.
     * This means callers can deserialise each emission independently.
     *
     * The flow completes when the remote peer closes the connection or [close]
     * is called on this side.
     */
    fun receive(): Flow<ByteArray>

    /**
     * Closes the channel and releases all underlying resources (socket, streams).
     * Safe to call multiple times.
     */
    suspend fun close()
}
