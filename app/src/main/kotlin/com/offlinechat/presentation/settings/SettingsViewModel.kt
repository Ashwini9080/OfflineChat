package com.offlinechat.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.domain.model.TransportType
import com.offlinechat.domain.repository.PreferencesRepository
import com.offlinechat.domain.usecase.GetLocalDeviceIdentityUseCase
import com.offlinechat.domain.usecase.UpdateDisplayNameUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val displayName: String = "",
    val deviceId: String = "",
    val isAutoDiscovery: Boolean = true,
    val preferredTransport: TransportType = TransportType.BLUETOOTH,
    val isDarkMode: Boolean = true,
    val isEditingName: Boolean = false,
    val editedName: String = "",
    val feedbackMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val updateDisplayNameUseCase: UpdateDisplayNameUseCase,
    private val getLocalDeviceIdentityUseCase: GetLocalDeviceIdentityUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            getLocalDeviceIdentityUseCase().collect { identity ->
                _uiState.value = _uiState.value.copy(
                    displayName = identity.displayName,
                    deviceId = identity.deviceId
                )
            }
        }
        viewModelScope.launch {
            preferencesRepository.isAutoDiscoveryEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(isAutoDiscovery = enabled)
            }
        }
        viewModelScope.launch {
            preferencesRepository.preferredTransport.collect { transport ->
                _uiState.value = _uiState.value.copy(preferredTransport = transport)
            }
        }
        viewModelScope.launch {
            preferencesRepository.isDarkMode.collect { dark ->
                _uiState.value = _uiState.value.copy(isDarkMode = dark)
            }
        }
    }

    fun startEditingName() {
        _uiState.value = _uiState.value.copy(
            isEditingName = true,
            editedName = _uiState.value.displayName
        )
    }

    fun onEditedNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(editedName = name)
    }

    fun saveEditedName() {
        viewModelScope.launch {
            val result = updateDisplayNameUseCase(_uiState.value.editedName)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isEditingName = false,
                    feedbackMessage = "Display name updated"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    feedbackMessage = result.exceptionOrNull()?.message ?: "Failed to update name"
                )
            }
        }
    }

    fun cancelEditingName() {
        _uiState.value = _uiState.value.copy(isEditingName = false)
    }

    fun toggleAutoDiscovery(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoDiscoveryEnabled(enabled)
        }
    }

    fun setPreferredTransport(transportType: TransportType) {
        viewModelScope.launch {
            preferencesRepository.setPreferredTransport(transportType)
        }
    }

    fun dismissFeedback() {
        _uiState.value = _uiState.value.copy(feedbackMessage = null)
    }
}
