package com.offlinechat.domain.repository

import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerTrustState
import kotlinx.coroutines.flow.Flow

interface PeerRepository {
    fun getPeers(): Flow<List<Peer>>
    fun getTrustedPeers(): Flow<List<Peer>>
    suspend fun getPeerById(deviceId: String): Peer?
    suspend fun saveOrUpdatePeer(peer: Peer)
    suspend fun markPeerTrusted(deviceId: String, isTrusted: Boolean = true)
    suspend fun updateTrustState(deviceId: String, trustState: PeerTrustState, safetyNumber: String? = null)
    suspend fun deletePeer(deviceId: String)
}
