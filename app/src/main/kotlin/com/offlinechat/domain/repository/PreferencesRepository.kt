package com.offlinechat.domain.repository

import com.offlinechat.domain.model.TransportType
import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    val displayName: Flow<String>
    val isAutoDiscoveryEnabled: Flow<Boolean>
    val preferredTransport: Flow<TransportType>
    val isDarkMode: Flow<Boolean>

    suspend fun setDisplayName(name: String)
    suspend fun setAutoDiscoveryEnabled(enabled: Boolean)
    suspend fun setPreferredTransport(transportType: TransportType)
    suspend fun setDarkMode(enabled: Boolean)
}
