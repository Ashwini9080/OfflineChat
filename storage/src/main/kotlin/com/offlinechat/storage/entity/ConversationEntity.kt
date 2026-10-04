package com.offlinechat.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the `conversations` table.
 *
 * Stores conversation metadata only — no message bodies.
 * Message bodies live in [MessageEntity] and are joined by [conversationId].
 *
 * @param id               Stable UUID derived from participant IDs (deterministic).
 * @param type             "DIRECT" or "GROUP".
 * @param participantIds   JSON-encoded list of [DeviceIdentity.id]s.
 *                         Stored as JSON string to avoid a join table for the
 *                         simple 1:1 case while still supporting group chat.
 * @param displayName      Explicit name for group chats; null for DIRECT.
 * @param lastMessageId    [MessageEntity.id] of the most recent message.
 * @param lastActivityAt   Unix epoch ms of the most recent activity.
 * @param unreadCount      Cached count of unread inbound messages.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "type")
    val type: String, // "DIRECT" | "GROUP"

    @ColumnInfo(name = "participant_ids")
    val participantIds: String, // JSON: ["deviceId1", "deviceId2"]

    @ColumnInfo(name = "display_name")
    val displayName: String?,

    @ColumnInfo(name = "last_message_id")
    val lastMessageId: String?,

    @ColumnInfo(name = "last_activity_at")
    val lastActivityAt: Long,

    @ColumnInfo(name = "unread_count")
    val unreadCount: Int,
)
