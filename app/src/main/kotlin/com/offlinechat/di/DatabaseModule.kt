package com.offlinechat.di

import android.content.Context
import androidx.room.Room
import com.offlinechat.data.local.database.AppDatabase
import com.offlinechat.data.local.database.ConversationDao
import com.offlinechat.data.local.database.MessageDao
import com.offlinechat.data.local.database.PeerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Indexes for fast message filtering and offline queue queries
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_receiver_id_status ON messages(receiver_id, status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_status ON messages(status)")

            // Unique peer_id index to prevent duplicate conversations and activity index for fast recency sort
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_peer_id ON conversations(peer_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_last_activity_at ON conversations(last_activity_at)")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE peers ADD COLUMN trust_state TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE peers ADD COLUMN safety_number TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE peers ADD COLUMN identity_fingerprint TEXT DEFAULT NULL")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "offline_chat_clean.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    @Provides
    fun providePeerDao(db: AppDatabase): PeerDao = db.peerDao()

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
