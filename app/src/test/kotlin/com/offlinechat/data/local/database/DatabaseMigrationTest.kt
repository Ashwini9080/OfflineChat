package com.offlinechat.data.local.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.offlinechat.di.DatabaseModule
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class DatabaseMigrationTest {

    @Test
    fun `migration from version 1 to 2 executes required index creation SQL statements`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        DatabaseModule.MIGRATION_1_2.migrate(db)

        verify {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_receiver_id_status ON messages(receiver_id, status)")
        }
        verify {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_status ON messages(status)")
        }
        verify {
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_peer_id ON conversations(peer_id)")
        }
        verify {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_last_activity_at ON conversations(last_activity_at)")
        }
    }
}
