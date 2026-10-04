package com.offlinechat.domain.repository

import com.offlinechat.domain.model.Conversation
import com.offlinechat.domain.model.TransportType
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getConversations(): Flow<List<Conversation>>
    suspend fun getConversationById(id: String): Conversation?
    suspend fun getOrCreateConversation(
        peerId: String,
        peerDisplayName: String,
        transportType: TransportType = TransportType.BLUETOOTH
    ): Conversation
    suspend fun updateLastMessage(conversationId: String, text: String, timestamp: Long)
    suspend fun markAsRead(conversationId: String)
    suspend fun incrementUnreadCount(conversationId: String)
    suspend fun deleteConversation(id: String)
}
