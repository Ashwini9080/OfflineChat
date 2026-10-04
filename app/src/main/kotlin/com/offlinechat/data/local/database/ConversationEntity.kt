package com.offlinechat.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

import androidx.room.Index

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["peer_id"], unique = true),
        Index(value = ["last_activity_at"])
    ]
)
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "peer_id")
    val peerId: String,

    @ColumnInfo(name = "peer_display_name")
    val peerDisplayName: String,

    @ColumnInfo(name = "last_message")
    val lastMessage: String,

    @ColumnInfo(name = "last_activity_at")
    val lastActivityAt: Long,

    @ColumnInfo(name = "unread_count")
    val unreadCount: Int,

    @ColumnInfo(name = "transport_type")
    val transportType: String
)
