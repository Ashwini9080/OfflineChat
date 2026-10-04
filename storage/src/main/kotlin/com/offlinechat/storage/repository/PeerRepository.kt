package com.offlinechat.storage.repository

import com.offlinechat.storage.dao.PeerDao
import com.offlinechat.storage.entity.PeerEntity
import kotlinx.coroutines.flow.Flow
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeerRepository @Inject constructor(
    private val peerDao: PeerDao,
) {

    fun observeTrustedPeers(): Flow<List<PeerEntity>> {
        return peerDao.getTrusted()
    }

    suspend fun getPeer(deviceId: String): PeerEntity? {
        return peerDao.getById(deviceId)
    }

    suspend fun saveOrUpdatePeer(
        deviceId: String,
        displayName: String,
        publicSigningKeyBytes: ByteArray,
        bluetoothAddress: String?,
        isTrusted: Boolean = false,
    ) {
        val existing = peerDao.getById(deviceId)
        val now = System.currentTimeMillis()
        val pubKeyB64 = Base64.getEncoder().encodeToString(publicSigningKeyBytes)

        if (existing == null) {
            peerDao.insert(
                PeerEntity(
                    deviceId = peer.deviceId,
                    displayName = peer.displayName,
                    publicSigningKeyBase64 = pubKeyB64,
                    bluetoothAddress = peer.bluetoothAddress,
                    isTrusted = isTrusted,
                    firstSeenAt = now,
                    lastSeenAt = now,
                )
            )
        } else {
            val updated = existing.copy(
                displayName = peer.displayName.ifBlank { existing.displayName },
                bluetoothAddress = peer.bluetoothAddress ?: existing.bluetoothAddress,
                lastSeenAt = now,
                isTrusted = existing.isTrusted || isTrusted,
            )
            peerDao.update(updated)
        }
    }

    suspend fun markTrusted(deviceId: String) {
        peerDao.markTrusted(deviceId)
    }

    suspend fun deletePeer(deviceId: String) {
        peerDao.deleteById(deviceId)
    }
}
