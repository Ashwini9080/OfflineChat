package com.offlinechat.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MessageEnvelope(
    val protocolVersion: Int = 1,
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long,
    val messageType: String, // "TEXT", "ACK", "HANDSHAKE"
    val payload: ByteArray,
    val nonce: ByteArray? = null,
    val signature: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEnvelope) return false

        return protocolVersion == other.protocolVersion &&
                messageId == other.messageId &&
                conversationId == other.conversationId &&
                senderId == other.senderId &&
                receiverId == other.receiverId &&
                timestamp == other.timestamp &&
                messageType == other.messageType &&
                payload.contentEquals(other.payload) &&
                nonce?.contentEquals(other.nonce ?: ByteArray(0)) ?: (other.nonce == null) &&
                signature?.contentEquals(other.signature ?: ByteArray(0)) ?: (other.signature == null)
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + messageId.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + receiverId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + messageType.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (nonce?.contentHashCode() ?: 0)
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }
}
