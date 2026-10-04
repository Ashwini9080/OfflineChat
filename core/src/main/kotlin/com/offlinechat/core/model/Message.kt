package com.offlinechat.core.model

/**
 * The core domain message — the business object that flows through the
 * application from UI down to storage.
 *
 * This is NOT the wire format. [messaging.MessageEnvelope] wraps and encrypts
 * this before transmission. Only [MessageContent.Text] is implemented in Phase 2.
 * File and group variants are stubbed here so the model is extensible without
 * breaking changes.
 *
 * @param id              UUID v4 — globally unique per message.
 * @param conversationId  Foreign key into [Conversation.id].
 * @param senderId        The [DeviceIdentity.id] of the originating device.
 * @param recipientId     The [DeviceIdentity.id] of the intended recipient.
 *                        For group messages (Phase 4) this is the group ID.
 * @param content         The actual payload — text, file metadata, or group invite.
 * @param sentAt          Sender's local Unix epoch ms. Used for display ordering.
 * @param deliveredAt     Set when the recipient's device sends an ACK.
 * @param readAt          Set when the recipient opens the conversation.
 * @param status          Lifecycle state of the message.
 * @param isOutbound      True if sent by this device; false if received.
 */
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val content: MessageContent,
    val sentAt: Long,
    val deliveredAt: Long? = null,
    val readAt: Long? = null,
    val status: MessageStatus = MessageStatus.PENDING,
    val isOutbound: Boolean,
)

// ── Message content variants ───────────────────────────────────────────────────

/**
 * Sealed hierarchy of message payload types.
 *
 * Adding a new content type is a single data class addition here — all
 * `when` expressions over [MessageContent] will produce a compile error
 * until the new branch is handled, guiding developers to update all call sites.
 */
sealed class MessageContent {

    /** A plain-text chat message. */
    data class Text(val body: String) : MessageContent()

    /**
     * File transfer metadata (Phase 3).
     * The actual bytes travel as [messaging.PayloadType.FILE_CHUNK] envelopes.
     *
     * @param name       Original filename (for display and reassembly).
     * @param mimeType   MIME type string, e.g. "image/jpeg".
     * @param sizeBytes  Total file size — used to show progress and validate reassembly.
     * @param chunkCount Number of [FILE_CHUNK] envelopes that will follow.
     */
    data class File(
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val chunkCount: Int,
    ) : MessageContent()

    /**
     * Group chat invitation (Phase 4).
     *
     * @param groupId   Identifier of the group being joined.
     * @param groupName Human-readable group name.
     * @param encryptedGroupKey  The group session key, encrypted for this recipient.
     */
    data class GroupInvite(
        val groupId: String,
        val groupName: String,
        val encryptedGroupKey: ByteArray,
    ) : MessageContent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GroupInvite) return false
            return groupId == other.groupId &&
                groupName == other.groupName &&
                encryptedGroupKey.contentEquals(other.encryptedGroupKey)
        }

        override fun hashCode(): Int {
            var result = groupId.hashCode()
            result = 31 * result + groupName.hashCode()
            result = 31 * result + encryptedGroupKey.contentHashCode()
            return result
        }
    }
}

// ── Message lifecycle states ───────────────────────────────────────────────────

/**
 * Represents the delivery lifecycle of a single [Message].
 *
 * State transitions (happy path):  PENDING → SENT → ACKNOWLEDGED
 * State transitions (failure path): PENDING → SENT → FAILED
 *                                   PENDING → FAILED (transport unavailable)
 */
enum class MessageStatus {

    /** Created locally; not yet handed to the transport layer. */
    PENDING,

    /** Successfully passed to the transport layer (RFCOMM write succeeded). */
    SENT,

    /** Recipient device sent an ACK envelope back confirming receipt. */
    ACKNOWLEDGED,

    /** The transport layer gave up after exhausting retries. */
    FAILED,
}
