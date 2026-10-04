package com.offlinechat.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY last_activity_at DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE peer_id = :peerId LIMIT 1")
    suspend fun getConversationByPeerId(peerId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET last_message = :text, last_activity_at = :timestamp WHERE id = :conversationId")
    suspend fun updateLastMessage(conversationId: String, text: String, timestamp: Long)

    @Query("UPDATE conversations SET unread_count = 0 WHERE id = :conversationId")
    suspend fun markAsRead(conversationId: String)

    @Query("UPDATE conversations SET unread_count = unread_count + 1 WHERE id = :conversationId")
    suspend fun incrementUnreadCount(conversationId: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)
}
