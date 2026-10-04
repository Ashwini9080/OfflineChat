package com.offlinechat.storage.di

import android.content.Context
import androidx.room.Room
import com.offlinechat.storage.AppDatabase
import com.offlinechat.storage.dao.ConversationDao
import com.offlinechat.storage.dao.MessageDao
import com.offlinechat.storage.dao.PeerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides the [AppDatabase] singleton and its DAOs.
 *
 * All DAOs are bound as singletons since they are lightweight objects backed
 * by the same database instance. Creating one DAO per call would be wasteful
 * and could lead to multiple database connection handles.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "offlinechat.db",
        )
            // IMPORTANT: Add migration objects here as the schema evolves.
            // Example: .addMigrations(MIGRATION_1_2)
            // Never use fallbackToDestructiveMigration() in production.
            .build()

    @Provides
    @Singleton
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    @Singleton
    fun providePeerDao(db: AppDatabase): PeerDao = db.peerDao()
}
