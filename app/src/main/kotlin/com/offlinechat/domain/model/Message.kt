package com.offlinechat.domain.model

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val text: String,
    val timestamp: Long,
    val status: MessageStatus = MessageStatus.PENDING,
    val isOutbound: Boolean = true
)
