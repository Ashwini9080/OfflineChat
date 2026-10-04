package com.offlinechat.data.repository

import com.offlinechat.data.local.preferences.AppPreferencesDataStore
import com.offlinechat.domain.model.TransportType
import com.offlinechat.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesRepositoryImpl @Inject constructor(
    private val dataStore: AppPreferencesDataStore
) : PreferencesRepository {

    override val displayName: Flow<String> = dataStore.displayName
    override val isAutoDiscoveryEnabled: Flow<Boolean> = dataStore.isAutoDiscoveryEnabled
    override val preferredTransport: Flow<TransportType> = dataStore.preferredTransport
    override val isDarkMode: Flow<Boolean> = dataStore.isDarkMode

    override suspend fun setDisplayName(name: String) {
        dataStore.setDisplayName(name)
    }

    override suspend fun setAutoDiscoveryEnabled(enabled: Boolean) {
        dataStore.setAutoDiscoveryEnabled(enabled)
    }

    override suspend fun setPreferredTransport(transportType: TransportType) {
        dataStore.setPreferredTransport(transportType)
    }

    override suspend fun setDarkMode(enabled: Boolean) {
        dataStore.setDarkMode(enabled)
    }
}
