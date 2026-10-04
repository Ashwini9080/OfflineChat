package com.offlinechat.domain.usecase

import com.offlinechat.domain.repository.PreferencesRepository
import javax.inject.Inject

class UpdateDisplayNameUseCase @Inject constructor(
    private val preferencesRepository: PreferencesRepository
) {
    suspend operator fun invoke(newName: String): Result<Unit> {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Display name cannot be blank"))
        }
        if (trimmed.length > 32) {
            return Result.failure(IllegalArgumentException("Display name cannot exceed 32 characters"))
        }
        preferencesRepository.setDisplayName(trimmed)
        return Result.success(Unit)
    }
}
