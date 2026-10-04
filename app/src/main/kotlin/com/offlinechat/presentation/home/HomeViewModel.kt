package com.offlinechat.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.data.discovery.DeviceDiscovery
import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.Conversation
import com.offlinechat.domain.usecase.GetConversationsUseCase
import com.offlinechat.domain.usecase.GetLocalDeviceIdentityUseCase
import com.offlinechat.domain.usecase.LocalDeviceIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val conversations: List<Conversation> = emptyList(),
    val localIdentity: LocalDeviceIdentity? = null,
    val isScanning: Boolean = false,
    val connectedPeerCount: Int = 0
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getConversationsUseCase: GetConversationsUseCase,
    private val getLocalDeviceIdentityUseCase: GetLocalDeviceIdentityUseCase,
    private val deviceDiscovery: DeviceDiscovery,
    private val messageTransport: MessageTransport
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        observeConversations()
        observeLocalIdentity()
        observeDiscoveryState()
    }

    private fun observeConversations() {
        viewModelScope.launch {
            getConversationsUseCase().collect { list ->
                _uiState.value = _uiState.value.copy(conversations = list)
            }
        }
    }

    private fun observeLocalIdentity() {
        viewModelScope.launch {
            getLocalDeviceIdentityUseCase().collect { identity ->
                _uiState.value = _uiState.value.copy(localIdentity = identity)
                // Auto-start presence discovery when identity is ready
                deviceDiscovery.startDiscovery(identity.displayName, identity.deviceId)
            }
        }
    }

    private fun observeDiscoveryState() {
        viewModelScope.launch {
            deviceDiscovery.isDiscovering.collect { discovering ->
                _uiState.value = _uiState.value.copy(isScanning = discovering)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            deviceDiscovery.stopDiscovery()
        }
    }
}
