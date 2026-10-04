package com.offlinechat.domain.repository

import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>
    suspend fun getMessageById(id: String): Message?
    suspend fun saveMessage(message: Message)
    suspend fun updateMessageStatus(id: String, status: MessageStatus)
    suspend fun getPendingOutboundMessagesForPeer(receiverId: String): List<Message>
    suspend fun deleteMessagesForConversation(conversationId: String)
}
