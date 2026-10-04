package com.offlinechat.domain.usecase

import com.offlinechat.domain.repository.PreferencesRepository
import com.offlinechat.security.IdentityManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class LocalDeviceIdentity(
    val deviceId: String,
    val displayName: String,
    val publicKeyBytes: ByteArray
)

class GetLocalDeviceIdentityUseCase @Inject constructor(
    private val identityManager: IdentityManager,
    private val preferencesRepository: PreferencesRepository
) {
    operator fun invoke(): Flow<LocalDeviceIdentity> {
        return preferencesRepository.displayName.map { name ->
            LocalDeviceIdentity(
                deviceId = identityManager.deviceId,
                displayName = name,
                publicKeyBytes = identityManager.publicKeyBytes
            )
        }
    }
}
