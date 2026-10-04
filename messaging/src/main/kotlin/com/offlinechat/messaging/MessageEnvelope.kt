package com.offlinechat.messaging

import kotlinx.serialization.Serializable

/**
 * The wire format for all data transmitted between devices.
 *
 * A [MessageEnvelope] wraps an encrypted, authenticated payload. It carries
 * enough metadata to route, deduplicate, and verify the message without
 * decrypting the payload first.
 *
 * ## Serialisation
 * Serialised with [kotlinx.serialization] as JSON. Binary formats (CBOR) can
 * be adopted later by changing the encoder without modifying this class.
 *
 * ## Security properties
 * - [encryptedPayload] is AES-256-GCM ciphertext produced by [security.SessionCrypto].
 * - [senderSignature] is an ECDSA P-256 signature covering the canonical signable
 *   bytes (see [signableBytes]), preventing modification in transit.
 * - [envelopeId] is used for deduplication — the receiver persists it and rejects
 *   replays within the same conversation.
 *
 * @param version           Schema version — allows evolving the format.
 * @param envelopeId        UUID v4. Used for deduplication and ACK correlation.
 * @param senderId          Sender's [DeviceIdentity.id].
 * @param recipientId       Recipient's [DeviceIdentity.id] (or group ID in Phase 4).
 * @param sentAt            Sender's Unix epoch ms — used for display ordering.
 * @param payloadType       Tells the receiver how to decode [encryptedPayload] after
 *                          decryption.
 * @param encryptedPayload  AES-GCM ciphertext (nonce prepended, tag appended).
 * @param senderSignature   ECDSA signature over [signableBytes].
 */
@Serializable
data class MessageEnvelope(
    val version: Int = CURRENT_VERSION,
    val envelopeId: String,
    val senderId: String,
    val recipientId: String,
    val sentAt: Long,
    val payloadType: PayloadType,
    val encryptedPayload: ByteArray,
    val senderSignature: ByteArray,
) {
    companion object {
        const val CURRENT_VERSION = 1

        /**
         * The bytes that are signed and verified.
         *
         * Covers all fields except [senderSignature] itself.
         * Using a "|"-separated string is acceptable here because the fields
         * (UUID, DeviceID, epoch ms) never contain "|".
         */
        fun signableBytes(
            version: Int,
            envelopeId: String,
            senderId: String,
            recipientId: String,
            sentAt: Long,
            payloadType: PayloadType,
            encryptedPayload: ByteArray,
        ): ByteArray {
            val header = "$version|$envelopeId|$senderId|$recipientId|$sentAt|${payloadType.name}"
                .toByteArray(Charsets.UTF_8)
            return header + encryptedPayload
        }
    }

    /** Convenience for signing/verifying this envelope. */
    fun signableBytes(): ByteArray = signableBytes(
        version, envelopeId, senderId, recipientId, sentAt, payloadType, encryptedPayload,
    )

    // ByteArray fields require manual equals/hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEnvelope) return false
        return version == other.version &&
            envelopeId == other.envelopeId &&
            senderId == other.senderId &&
            recipientId == other.recipientId &&
            sentAt == other.sentAt &&
            payloadType == other.payloadType &&
            encryptedPayload.contentEquals(other.encryptedPayload) &&
            senderSignature.contentEquals(other.senderSignature)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + envelopeId.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + recipientId.hashCode()
        result = 31 * result + sentAt.hashCode()
        result = 31 * result + payloadType.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        result = 31 * result + senderSignature.contentHashCode()
        return result
    }
}

// ── Payload type registry ─────────────────────────────────────────────────────

/**
 * Identifies the type of data inside [MessageEnvelope.encryptedPayload].
 *
 * The receiver decrypts the payload first, then uses [PayloadType] to determine
 * how to deserialise the plaintext bytes.
 *
 * New payload types can be added here without breaking existing devices — unknown
 * types are dropped gracefully by the receiver.
 */
enum class PayloadType {

    /** A [core.model.MessageContent.Text] chat message. */
    TEXT_MESSAGE,

    /**
     * Delivery acknowledgement — sent by the receiver back to the sender when a
     * [TEXT_MESSAGE] (or any other payload) is successfully stored.
     * Payload contains the [MessageEnvelope.envelopeId] of the acknowledged message.
     */
    ACK,

    /**
     * Key exchange payload — used during the RFCOMM handshake.
     * (Phase 2: handled at the [transport.bluetooth.BluetoothTransport] layer,
     * not by [MessageReceiver].)
     */
    KEY_EXCHANGE,

    /**
     * Group chat invitation (Phase 4).
     * Payload is a serialised [core.model.MessageContent.GroupInvite].
     */
    GROUP_INVITE,

    /**
     * File chunk (Phase 3).
     * Payload contains chunk metadata + raw bytes for one segment of a file transfer.
     */
    FILE_CHUNK,
}
