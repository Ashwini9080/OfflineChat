package com.offlinechat.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.offlinechat.domain.model.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "offline_chat_preferences")

@Singleton
class AppPreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_DISPLAY_NAME = stringPreferencesKey("device_display_name")
        private val KEY_AUTO_DISCOVERY = booleanPreferencesKey("auto_discovery_enabled")
        private val KEY_PREFERRED_TRANSPORT = stringPreferencesKey("preferred_transport")
        private val KEY_DARK_MODE = booleanPreferencesKey("dark_mode_enabled")
        private val KEY_UPDATE_SERVER_URL = stringPreferencesKey("update_server_url")
    }

    val updateServerUrl: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            prefs[KEY_UPDATE_SERVER_URL] ?: "http://10.33.160.61:8080"
        }

    val displayName: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            prefs[KEY_DISPLAY_NAME] ?: android.os.Build.MODEL
        }

    val isAutoDiscoveryEnabled: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            prefs[KEY_AUTO_DISCOVERY] ?: true
        }

    val preferredTransport: Flow<TransportType> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            val name = prefs[KEY_PREFERRED_TRANSPORT] ?: TransportType.BLUETOOTH.name
            runCatching { TransportType.valueOf(name) }.getOrDefault(TransportType.BLUETOOTH)
        }

    val isDarkMode: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            prefs[KEY_DARK_MODE] ?: true
        }

    suspend fun setUpdateServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_UPDATE_SERVER_URL] = url
        }
    }

    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DISPLAY_NAME] = name
        }
    }

    suspend fun setAutoDiscoveryEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_DISCOVERY] = enabled
        }
    }

    suspend fun setPreferredTransport(transportType: TransportType) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PREFERRED_TRANSPORT] = transportType.name
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DARK_MODE] = enabled
        }
    }
}
