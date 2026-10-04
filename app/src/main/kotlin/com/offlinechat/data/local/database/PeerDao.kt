package com.offlinechat.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY last_seen_at DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE is_trusted = 1 ORDER BY last_seen_at DESC")
    fun getTrustedPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE device_id = :deviceId LIMIT 1")
    suspend fun getPeerById(deviceId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Update
    suspend fun updatePeer(peer: PeerEntity)

    @Query("UPDATE peers SET is_trusted = :isTrusted WHERE device_id = :deviceId")
    suspend fun updateTrustStatus(deviceId: String, isTrusted: Boolean)

    @Query("UPDATE peers SET trust_state = :trustState, safety_number = :safetyNumber WHERE device_id = :deviceId")
    suspend fun updateTrustState(deviceId: String, trustState: String, safetyNumber: String?)

    @Query("DELETE FROM peers WHERE device_id = :deviceId")
    suspend fun deletePeer(deviceId: String)
}
