package com.offlinechat.domain.model

data class Conversation(
    val id: String,
    val peerId: String,
    val peerDisplayName: String,
    val lastMessage: String = "",
    val lastActivityAt: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val transportType: TransportType = TransportType.BLUETOOTH
)
