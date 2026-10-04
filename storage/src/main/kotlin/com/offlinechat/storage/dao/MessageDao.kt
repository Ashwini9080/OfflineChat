package com.offlinechat.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.offlinechat.storage.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the `messages` table.
 *
 * ## Query design notes
 * - All list queries are backed by [Flow] so Room automatically re-emits when
 *   the underlying table changes. ViewModels collect these flows to keep the
 *   UI reactive without polling.
 * - [getByConversationId] orders by [sentAt] ASC so the UI can display messages
 *   in chronological order without sorting in memory.
 * - [getUndelivered] is used by the retry worker to find messages still in
 *   PENDING or SENT status.
 */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    /** Upsert — used when we need to update status (SENT → ACKNOWLEDGED). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Query("""
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY sent_at ASC
    """)
    fun getByConversationId(conversationId: String): Flow<List<MessageEntity>>

    /** Returns the N most recent messages for a conversation — used for list preview. */
    @Query("""
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY sent_at DESC
        LIMIT :limit
    """)
    suspend fun getRecentMessages(conversationId: String, limit: Int = 50): List<MessageEntity>

    /** Deduplication check — returns true if an envelope with this ID has been stored. */
    @Query("SELECT COUNT(*) FROM messages WHERE envelope_id = :envelopeId")
    suspend fun envelopeExists(envelopeId: String): Int

    /** Returns messages not yet confirmed delivered — used by the retry worker. */
    @Query("""
        SELECT * FROM messages
        WHERE status IN ('PENDING', 'SENT')
        AND is_outbound = 1
        ORDER BY sent_at ASC
    """)
    suspend fun getUndelivered(): List<MessageEntity>

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE messages SET read_at = :readAt WHERE conversation_id = :conversationId AND read_at IS NULL AND is_outbound = 0")
    suspend fun markConversationRead(conversationId: String, readAt: Long)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)
}
