package com.offlinechat.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversation_id", "timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "sender_id")
    val senderId: String,

    @ColumnInfo(name = "receiver_id")
    val receiverId: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "is_outbound")
    val isOutbound: Boolean
)
