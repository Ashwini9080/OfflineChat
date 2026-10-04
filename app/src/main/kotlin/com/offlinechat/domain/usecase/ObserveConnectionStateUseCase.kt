package com.offlinechat.domain.usecase

import com.offlinechat.domain.connection.ConnectionManager
import com.offlinechat.domain.model.PeerConnectionState
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveConnectionStateUseCase @Inject constructor(
    private val connectionManager: ConnectionManager
) {
    operator fun invoke(peerId: String): Flow<PeerConnectionState> {
        return connectionManager.observeConnectionState(peerId)
    }
}
