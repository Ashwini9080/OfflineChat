package com.offlinechat.transport.api

/**
 * Events emitted by a [Transport] to signal changes in the network topology
 * or connection state.
 *
 * Collected by [manager.TransportManager] and forwarded to the :messaging layer
 * and UI ViewModels via a shared [kotlinx.coroutines.flow.StateFlow].
 *
 * All events carry enough context for the receiver to take action without
 * needing to call back into the transport.
 */
sealed class TransportEvent {

    /**
     * A new peer has been discovered and its [security.model.DeviceCertificate]
     * has been verified. The peer is now eligible for connection.
     */
    data class PeerDiscovered(val peer: PeerDevice) : TransportEvent()

    /**
     * A previously discovered peer is no longer reachable (BLE signal lost or
     * scan timeout). Open [TransportChannel]s to this peer are NOT automatically
     * closed — the :messaging layer decides whether to close or retry.
     */
    data class PeerLost(val peerId: String) : TransportEvent()

    /**
     * A [TransportChannel] to [peer] has been successfully established and is
     * ready for [TransportChannel.send] / [TransportChannel.receive].
     */
    data class ChannelOpened(
        val peer: PeerDevice,
        val channel: TransportChannel,
    ) : TransportEvent()

    /**
     * An existing [TransportChannel] to [peerId] has been closed, either by the
     * remote side or due to a network error.
     */
    data class ChannelClosed(
        val peerId: String,
        val reason: String,
    ) : TransportEvent()

    /**
     * A connection attempt to [peerId] failed before a channel could be opened.
     */
    data class ConnectionFailed(
        val peerId: String,
        val reason: String,
    ) : TransportEvent()

    /**
     * An internal transport error that does not correspond to a specific peer.
     * The transport may still be usable after this event.
     */
    data class TransportError(
        val error: Throwable,
        val detail: String,
    ) : TransportEvent()
}
