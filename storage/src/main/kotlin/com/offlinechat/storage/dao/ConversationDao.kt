package com.offlinechat.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.offlinechat.storage.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    /** All conversations sorted by most recent activity — the home screen list. */
    @Query("SELECT * FROM conversations ORDER BY last_activity_at DESC")
    fun getAllSortedByActivity(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET unread_count = 0 WHERE id = :conversationId")
    suspend fun clearUnreadCount(conversationId: String)

    @Query("UPDATE conversations SET unread_count = unread_count + 1 WHERE id = :conversationId")
    suspend fun incrementUnreadCount(conversationId: String)

    @Query("UPDATE conversations SET last_message_id = :messageId, last_activity_at = :activityAt WHERE id = :conversationId")
    suspend fun updateLastMessage(conversationId: String, messageId: String, activityAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)
}
