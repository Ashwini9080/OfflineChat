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

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "offline_chat_clean.db"
        ).build()
    }

    @Provides
    fun providePeerDao(db: AppDatabase): PeerDao = db.peerDao()

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
}
