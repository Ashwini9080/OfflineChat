package com.offlinechat.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.offlinechat.storage.entity.PeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(peer: PeerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)

    @Update
    suspend fun update(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE device_id = :deviceId LIMIT 1")
    suspend fun getById(deviceId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE bluetooth_address = :address LIMIT 1")
    suspend fun getByBluetoothAddress(address: String): PeerEntity?

    /** All trusted peers — used to populate the conversations list. */
    @Query("SELECT * FROM peers WHERE is_trusted = 1 ORDER BY last_seen_at DESC")
    fun getTrusted(): Flow<List<PeerEntity>>

    @Query("UPDATE peers SET is_trusted = 1 WHERE device_id = :deviceId")
    suspend fun markTrusted(deviceId: String)

    @Query("UPDATE peers SET last_seen_at = :timestamp WHERE device_id = :deviceId")
    suspend fun updateLastSeen(deviceId: String, timestamp: Long)

    @Query("DELETE FROM peers WHERE device_id = :deviceId")
    suspend fun deleteById(deviceId: String)
}
