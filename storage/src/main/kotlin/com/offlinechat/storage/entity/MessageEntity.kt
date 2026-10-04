package com.offlinechat.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a single chat message stored in the `messages` table.
 *
 * ## Design decisions
 * - [encryptedBody] stores the AES-GCM ciphertext of the [MessageContent] plaintext.
 *   The nonce is prepended (first 12 bytes) following [security.SessionCrypto]'s
 *   convention. This means the database file does not contain readable message bodies,
 *   even if the SQLite file is extracted from an unrooted device.
 * - [contentType] is stored as a string for forward compatibility — new content types
 *   added in later app versions will survive Room migration gracefully.
 * - [conversationId] is a foreign key so cascade-deleting a conversation also removes
 *   its messages. The index on [conversationId] + [sentAt] supports the common query
 *   "get all messages in conversation X ordered by time".
 *
 * @param id              UUID v4, matches [Message.id].
 * @param conversationId  Foreign key into [ConversationEntity.id].
 * @param senderId        [DeviceIdentity.id] of the originating device.
 * @param recipientId     [DeviceIdentity.id] of the intended recipient.
 * @param contentType     String form of [PayloadType] (e.g., "TEXT_MESSAGE").
 * @param encryptedBody   AES-GCM ciphertext (nonce || ciphertext) of the message body.
 * @param status          String form of [MessageStatus].
 * @param sentAt          Unix epoch ms.
 * @param deliveredAt     Unix epoch ms, or null if not yet acknowledged.
 * @param readAt          Unix epoch ms, or null if not yet read.
 * @param isOutbound      True if sent by this device.
 * @param envelopeId      The [MessageEnvelope.envelopeId] — persisted for deduplication
 *                        on the receiver side (survives process restarts).
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "sent_at"]),
        Index(value = ["envelope_id"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "sender_id")
    val senderId: String,

    @ColumnInfo(name = "recipient_id")
    val recipientId: String,

    @ColumnInfo(name = "content_type")
    val contentType: String,

    @ColumnInfo(name = "encrypted_body", typeAffinity = ColumnInfo.BLOB)
    val encryptedBody: ByteArray,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "sent_at")
    val sentAt: Long,

    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long?,

    @ColumnInfo(name = "read_at")
    val readAt: Long?,

    @ColumnInfo(name = "is_outbound")
    val isOutbound: Boolean,

    @ColumnInfo(name = "envelope_id")
    val envelopeId: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        return id == other.id && encryptedBody.contentEquals(other.encryptedBody)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + encryptedBody.contentHashCode()
        return result
    }
}
