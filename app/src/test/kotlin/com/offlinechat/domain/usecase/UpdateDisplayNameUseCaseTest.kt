package com.offlinechat.domain.usecase

import com.offlinechat.domain.repository.PreferencesRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateDisplayNameUseCaseTest {

    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var useCase: UpdateDisplayNameUseCase

    @Before
    fun setUp() {
        preferencesRepository = mockk(relaxed = true)
        useCase = UpdateDisplayNameUseCase(preferencesRepository)
    }

    @Test
    fun `invoke with blank name returns failure`() = runTest {
        val result = useCase("   ")
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { preferencesRepository.setDisplayName(any()) }
    }

    @Test
    fun `invoke with name exceeding 32 characters returns failure`() = runTest {
        val longName = "A".repeat(33)
        val result = useCase(longName)
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { preferencesRepository.setDisplayName(any()) }
    }

    @Test
    fun `invoke with valid name updates DataStore preferences`() = runTest {
        val result = useCase("Alice Offline")
        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { preferencesRepository.setDisplayName("Alice Offline") }
    }
}
