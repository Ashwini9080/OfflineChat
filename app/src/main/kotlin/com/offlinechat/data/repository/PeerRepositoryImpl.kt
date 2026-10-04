package com.offlinechat.data.repository

import com.offlinechat.data.local.database.PeerDao
import com.offlinechat.data.local.database.PeerEntity
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import com.offlinechat.domain.repository.PeerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeerRepositoryImpl @Inject constructor(
    private val peerDao: PeerDao
) : PeerRepository {

    override fun getPeers(): Flow<List<Peer>> {
        return peerDao.getAllPeers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getTrustedPeers(): Flow<List<Peer>> {
        return peerDao.getTrustedPeers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getPeerById(deviceId: String): Peer? {
        return peerDao.getPeerById(deviceId)?.toDomain()
    }

    override suspend fun saveOrUpdatePeer(peer: Peer) {
        val pubKeyB64 = if (peer.publicKeyBytes.isNotEmpty()) {
            Base64.getEncoder().encodeToString(peer.publicKeyBytes)
        } else {
            ""
        }
        val entity = PeerEntity(
            deviceId = peer.deviceId,
            displayName = peer.displayName,
            bluetoothAddress = peer.bluetoothAddress,
            publicKeyBase64 = pubKeyB64,
            rssi = peer.rssi,
            isTrusted = peer.isTrusted,
            transportType = peer.transportType.name,
            lastSeenAt = peer.lastSeenAt
        )
        peerDao.upsertPeer(entity)
    }

    override suspend fun markPeerTrusted(deviceId: String, isTrusted: Boolean) {
        peerDao.updateTrustStatus(deviceId, isTrusted)
    }

    override suspend fun deletePeer(deviceId: String) {
        peerDao.deletePeer(deviceId)
    }

    private fun PeerEntity.toDomain(): Peer {
        val pubKeyBytes = try {
            if (publicKeyBase64.isNotEmpty()) Base64.getDecoder().decode(publicKeyBase64) else ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        }

        return Peer(
            deviceId = deviceId,
            displayName = displayName,
            bluetoothAddress = bluetoothAddress,
            publicKeyBytes = pubKeyBytes,
            rssi = rssi,
            isTrusted = isTrusted,
            isConnected = false,
            transportType = runCatching { TransportType.valueOf(transportType) }.getOrDefault(TransportType.BLUETOOTH),
            lastSeenAt = lastSeenAt
        )
    }
}
