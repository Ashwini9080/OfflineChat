package com.offlinechat.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the `pending_messages` table — the durable outbound message queue.
 *
 * When [messaging.MessageSender.send] fails (peer offline, BT error), the
 * message is added here. A background job periodically queries this table and
 * retries delivery for messages whose [nextRetryAt] has passed.
 *
 * This ensures messages survive:
 * - Process death (app killed while peer was unreachable)
 * - Bluetooth radio turning off and back on
 * - The user putting the device in their pocket
 *
 * @param messageId      Foreign key into [MessageEntity.id]. The full message
 *                       content is stored in [MessageEntity] — this table only
 *                       tracks retry metadata.
 * @param recipientId    [DeviceIdentity.id] of the intended recipient.
 * @param retryCount     Number of delivery attempts made so far.
 * @param nextRetryAt    Unix epoch ms after which the next delivery attempt is allowed.
 *                       Implements exponential backoff: 30s, 1m, 2m, 5m, 10m, 30m.
 * @param createdAt      When the message was first queued.
 */
@Entity(
    tableName = "pending_messages",
    indices = [
        Index(value = ["recipient_id"]),
        Index(value = ["next_retry_at"]),
    ],
)
data class PendingMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "recipient_id")
    val recipientId: String,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
