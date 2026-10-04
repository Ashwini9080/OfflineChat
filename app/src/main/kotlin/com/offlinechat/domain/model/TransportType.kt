package com.offlinechat.domain.model

enum class TransportType(val displayName: String) {
    BLUETOOTH("Bluetooth"),
    WIFI_DIRECT("Wi-Fi Direct")
}

/**
 * Robust message delivery state model.
 *
 * PENDING   - Saved locally in SQLite Room database, awaiting connection or transport dispatch.
 * SENDING   - Currently in-flight across the transport socket stream.
 * SENT      - Application successfully handed the framed envelope to the transport socket.
 * DELIVERED - Receiving device sent an explicit DELIVERY_ACK receipt.
 * FAILED    - Transport write failed, connection dropped, or delivery ACK timed out.
 */
enum class MessageStatus {
    PENDING,
    SENDING,
    SENT,
    DELIVERED,
    FAILED
}
