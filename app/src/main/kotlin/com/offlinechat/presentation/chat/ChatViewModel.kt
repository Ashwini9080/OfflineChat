package com.offlinechat.presentation.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.repository.MessageRepository
import com.offlinechat.domain.repository.PeerRepository
import com.offlinechat.domain.usecase.ConnectPeerUseCase
import com.offlinechat.domain.usecase.GetLocalDeviceIdentityUseCase
import com.offlinechat.domain.usecase.GetMessagesUseCase
import com.offlinechat.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val conversationId: String = "",
    val peerId: String = "",
    val peerDisplayName: String = "",
    val localDeviceId: String = "",
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val connectionState: PeerConnectionState = PeerConnectionState.Disconnected,
    val isReconnecting: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val getMessagesUseCase: GetMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val connectPeerUseCase: ConnectPeerUseCase,
    private val getLocalDeviceIdentityUseCase: GetLocalDeviceIdentityUseCase,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val messageTransport: MessageTransport
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        val convoId: String = savedStateHandle["conversationId"] ?: ""
        val pId: String = savedStateHandle["peerId"] ?: ""
        val pName: String = savedStateHandle["peerDisplayName"] ?: "Peer"

        _uiState.value = _uiState.value.copy(
            conversationId = convoId,
            peerId = pId,
            peerDisplayName = pName
        )

        observeLocalIdentity()
        observeMessages(convoId)
        observeConnectionState(pId)
        markRead(convoId)
    }

    private fun observeLocalIdentity() {
        viewModelScope.launch {
            getLocalDeviceIdentityUseCase().collect { identity ->
                _uiState.value = _uiState.value.copy(localDeviceId = identity.deviceId)
            }
        }
    }

    private fun observeMessages(convoId: String) {
        viewModelScope.launch {
            getMessagesUseCase(convoId).collect { msgList ->
                _uiState.value = _uiState.value.copy(messages = msgList)
            }
        }
    }

    private fun observeConnectionState(pId: String) {
        viewModelScope.launch {
            messageTransport.observeConnectionState(pId).collect { state ->
                _uiState.value = _uiState.value.copy(connectionState = state)
            }
        }
    }

    private fun markRead(convoId: String) {
        viewModelScope.launch {
            getMessagesUseCase.markAsRead(convoId)
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun reconnect() {
        val pId = _uiState.value.peerId
        if (pId.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isReconnecting = true)
            val peer = peerRepository.getPeerById(pId)
            if (peer != null) {
                val result = connectPeerUseCase(peer)
                _uiState.value = _uiState.value.copy(
                    isReconnecting = false,
                    errorMessage = if (result.isFailure) result.exceptionOrNull()?.message ?: "Reconnection failed" else null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isReconnecting = false,
                    errorMessage = "Peer hardware address not found"
                )
            }
        }
    }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isEmpty() || state.isSending) return

        _uiState.value = _uiState.value.copy(inputText = "", isSending = true)

        viewModelScope.launch {
            val result = sendMessageUseCase(
                conversationId = state.conversationId,
                senderId = state.localDeviceId,
                receiverId = state.peerId,
                text = text
            )

            _uiState.value = _uiState.value.copy(isSending = false)

            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = result.exceptionOrNull()?.message ?: "Message queued offline"
                )
            }
        }
    }

    fun retryMessage(message: Message) {
        viewModelScope.launch {
            sendMessageUseCase(
                conversationId = message.conversationId,
                senderId = message.senderId,
                receiverId = message.receiverId,
                text = message.text
            )
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
