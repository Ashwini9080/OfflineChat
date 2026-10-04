package com.offlinechat.ui.screen.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.core.model.Message
import com.offlinechat.core.model.MessageContent
import com.offlinechat.core.model.MessageStatus
import com.offlinechat.core.result.AppResult
import com.offlinechat.core.util.TimeProvider
import com.offlinechat.core.util.UuidFactory
import com.offlinechat.messaging.ConversationManager
import com.offlinechat.messaging.MessageSender
import com.offlinechat.security.IdentityManager
import com.offlinechat.storage.repository.ConversationRepository
import com.offlinechat.storage.repository.MessageRepository
import com.offlinechat.transport.manager.TransportManager
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
    val isChannelConnected: Boolean = false,
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val conversationManager: ConversationManager,
    private val messageSender: MessageSender,
    private val transportManager: TransportManager,
    private val identityManager: IdentityManager,
    private val timeProvider: TimeProvider,
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

        loadLocalIdentity()
        observeStoredMessages(convoId)
        observeLiveMessages(convoId)
        checkChannelState(pId)
        clearUnread(convoId)
    }

    private fun loadLocalIdentity() {
        val identityRes = identityManager.getLocalIdentity()
        if (identityRes is AppResult.Success) {
            _uiState.value = _uiState.value.copy(localDeviceId = identityRes.data.id)
        }
    }

    private fun observeStoredMessages(convoId: String) {
        viewModelScope.launch {
            messageRepository.observeMessages(convoId).collect { msgList ->
                _uiState.value = _uiState.value.copy(messages = msgList)
            }
        }
    }

    private fun observeLiveMessages(convoId: String) {
        viewModelScope.launch {
            conversationManager.messages.collect { liveMsg ->
                if (liveMsg.conversationId == convoId) {
                    messageRepository.saveMessage(liveMsg)
                    conversationRepository.updateLastMessage(convoId, liveMsg.id, liveMsg.sentAt)
                }
            }
        }
    }

    private fun checkChannelState(peerId: String) {
        val hasChannel = transportManager.getOpenChannel(peerId) != null
        _uiState.value = _uiState.value.copy(isChannelConnected = hasChannel)
    }

    private fun clearUnread(convoId: String) {
        viewModelScope.launch {
            conversationRepository.clearUnreadCount(convoId)
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isBlank() || state.isSending) return

        val messageId = UuidFactory.newId()
        val now = timeProvider.nowMillis()
        val senderId = state.localDeviceId

        val outboundMessage = Message(
            id = messageId,
            conversationId = state.conversationId,
            senderId = senderId,
            recipientId = state.peerId,
            content = MessageContent.Text(text),
            sentAt = now,
            status = MessageStatus.PENDING,
            isOutbound = true
        )

        _uiState.value = _uiState.value.copy(inputText = "", isSending = true)

        viewModelScope.launch {
            // 1. Save locally with PENDING
            messageRepository.saveMessage(outboundMessage)
            conversationRepository.updateLastMessage(state.conversationId, messageId, now)

            // 2. Transmit over transport channel
            val result = messageSender.send(outboundMessage)
            val updatedStatus = if (result is AppResult.Success) {
                MessageStatus.SENT
            } else {
                MessageStatus.FAILED
            }

            // 3. Update database
            messageRepository.updateMessageStatus(messageId, updatedStatus)
            _uiState.value = _uiState.value.copy(
                isSending = false,
                isChannelConnected = transportManager.getOpenChannel(state.peerId) != null
            )
        }
    }

    fun retryMessage(message: Message) {
        viewModelScope.launch {
            messageRepository.updateMessageStatus(message.id, MessageStatus.PENDING)
            val res = messageSender.send(message)
            val newStatus = if (res is AppResult.Success) MessageStatus.SENT else MessageStatus.FAILED
            messageRepository.updateMessageStatus(message.id, newStatus)
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
