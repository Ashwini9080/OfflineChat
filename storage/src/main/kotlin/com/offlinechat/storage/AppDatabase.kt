package com.offlinechat.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import com.offlinechat.storage.dao.ConversationDao
import com.offlinechat.storage.dao.MessageDao
import com.offlinechat.storage.dao.PeerDao
import com.offlinechat.storage.entity.ConversationEntity
import com.offlinechat.storage.entity.MessageEntity
import com.offlinechat.storage.entity.PeerEntity
import com.offlinechat.storage.entity.PendingMessageEntity

/**
 * Root Room database for OfflineChat.
 *
 * ## Schema version history
 * | Version | Changes |
 * |---|---|
 * | 1 | Initial schema: messages, conversations, peers, pending_messages |
 *
 * ## Migration strategy
 * All future schema changes MUST provide a [androidx.room.migration.Migration]
 * object and be added to the `addMigrations(...)` call in [di.StorageModule].
 * Never rely on `fallbackToDestructiveMigration()` in production — it deletes
 * all user messages.
 *
 * ## At-rest encryption
 * The Room database file is located in the app's private data directory
 * (`/data/data/<packageName>/databases/`), which is not accessible without root.
 * For devices where this is insufficient, consider SQLCipher in Phase 3.
 * Message content is additionally encrypted at the field level ([MessageEntity.encryptedBody]).
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        PeerEntity::class,
        PendingMessageEntity::class,
    ],
    version = 1,
    exportSchema = true, // Generate schema JSON for version history tracking
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun peerDao(): PeerDao
}
