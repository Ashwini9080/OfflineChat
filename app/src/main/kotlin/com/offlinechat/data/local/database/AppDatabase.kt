package com.offlinechat.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PeerEntity::class,
        ConversationEntity::class,
        MessageEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
