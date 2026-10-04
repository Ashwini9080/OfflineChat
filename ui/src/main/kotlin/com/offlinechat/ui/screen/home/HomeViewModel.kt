package com.offlinechat.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.core.model.Conversation
import com.offlinechat.core.model.DeviceIdentity
import com.offlinechat.core.result.AppResult
import com.offlinechat.security.IdentityManager
import com.offlinechat.storage.repository.ConversationRepository
import com.offlinechat.transport.manager.TransportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val identity: DeviceIdentity? = null,
    val conversations: List<Conversation> = emptyList(),
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val connectedPeerCount: Int = 0,
    val errorMessage: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val conversationRepository: ConversationRepository,
    private val transportManager: TransportManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadIdentity()
        observeConversations()
        observeTransportState()
    }

    fun loadIdentity() {
        viewModelScope.launch {
            when (val result = identityManager.ensureIdentityExists()) {
                is AppResult.Success -> {
                    _uiState.value = _uiState.value.copy(identity = result.data)
                    // Start BLE advertising by default
                    startAdvertising(result.data)
                }
                is AppResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Could not initialize device cryptographic identity: ${result.error}"
                    )
                }
            }
        }
    }

    private fun observeConversations() {
        viewModelScope.launch {
            conversationRepository.observeConversations().collect { convos ->
                _uiState.value = _uiState.value.copy(conversations = convos)
            }
        }
    }

    private fun observeTransportState() {
        viewModelScope.launch {
            val connectedPeers = mutableSetOf<String>()
            transportManager.events.collect { event ->
                when (event) {
                    is com.offlinechat.transport.api.TransportEvent.ChannelOpened -> {
                        connectedPeers.add(event.peer.deviceId)
                        _uiState.value = _uiState.value.copy(connectedPeerCount = connectedPeers.size)
                    }
                    is com.offlinechat.transport.api.TransportEvent.ChannelClosed -> {
                        connectedPeers.remove(event.peerId)
                        _uiState.value = _uiState.value.copy(connectedPeerCount = connectedPeers.size)
                    }
                    else -> Unit
                }
            }
        }
    }

    fun startAdvertising(identity: DeviceIdentity? = _uiState.value.identity) {
        if (identity == null) return
        viewModelScope.launch {
            transportManager.start(identity)
            _uiState.value = _uiState.value.copy(isAdvertising = true)
        }
    }

    fun stopAdvertising() {
        viewModelScope.launch {
            transportManager.shutdown()
            _uiState.value = _uiState.value.copy(isAdvertising = false)
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
