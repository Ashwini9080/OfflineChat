package com.offlinechat.ui.screen.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.core.model.DeviceIdentity
import com.offlinechat.core.result.AppResult
import com.offlinechat.security.IdentityManager
import com.offlinechat.storage.repository.ConversationRepository
import com.offlinechat.storage.repository.PeerRepository
import com.offlinechat.transport.api.PeerDevice
import com.offlinechat.transport.api.TransportEvent
import com.offlinechat.transport.manager.TransportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoveryUiState(
    val isScanning: Boolean = false,
    val localIdentity: DeviceIdentity? = null,
    val discoveredPeers: List<PeerDevice> = emptyList(),
    val connectingPeerId: String? = null,
    val connectedPeerIds: Set<String> = emptySet(),
    val pendingTrustPeer: PeerDevice? = null,
    val errorMessage: String? = null,
)

sealed interface DiscoveryNavEvent {
    data class NavigateToChat(
        val conversationId: String,
        val peerId: String,
        val peerDisplayName: String
    ) : DiscoveryNavEvent
}

@HiltViewModel
class DeviceDiscoveryViewModel @Inject constructor(
    private val transportManager: TransportManager,
    private val identityManager: IdentityManager,
    private val peerRepository: PeerRepository,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    private val _navEvents = MutableSharedFlow<DiscoveryNavEvent>()
    val navEvents: SharedFlow<DiscoveryNavEvent> = _navEvents.asSharedFlow()

    private val pendingTrustedPeerIds = mutableSetOf<String>()

    init {
        loadIdentity()
        observeTransportEvents()
    }

    private fun loadIdentity() {
        viewModelScope.launch {
            when (val res = identityManager.ensureIdentityExists()) {
                is AppResult.Success -> {
                    _uiState.value = _uiState.value.copy(localIdentity = res.data)
                    startScanning()
                }
                is AppResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Cannot access identity: ${res.error}"
                    )
                }
            }
        }
    }

    fun startScanning() {
        val identity = _uiState.value.localIdentity ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)
            transportManager.start(identity)
        }
    }

    fun stopScanning() {
        _uiState.value = _uiState.value.copy(isScanning = false)
    }

    private fun observeTransportEvents() {
        viewModelScope.launch {
            transportManager.events.collect { event ->
                when (event) {
                    is TransportEvent.PeerDiscovered -> {
                        val current = _uiState.value.discoveredPeers.toMutableList()
                        val idx = current.indexOfFirst { it.deviceId == event.peer.deviceId }
                        if (idx >= 0) {
                            current[idx] = event.peer
                        } else {
                            current.add(event.peer)
                        }
                        // Sort by strongest signal (RSSI highest first)
                        current.sortByDescending { it.rssi }
                        _uiState.value = _uiState.value.copy(discoveredPeers = current)
                    }
                    is TransportEvent.PeerLost -> {
                        val current = _uiState.value.discoveredPeers.filterNot { it.deviceId == event.peerId }
                        _uiState.value = _uiState.value.copy(discoveredPeers = current)
                    }
                    is TransportEvent.ChannelOpened -> {
                        val peerId = event.peer.deviceId
                        val connected = _uiState.value.connectedPeerIds + peerId
                        _uiState.value = _uiState.value.copy(
                            connectingPeerId = null,
                            connectedPeerIds = connected
                        )

                        // Bind verified public signing key and finalize trust upon authenticated handshake completion
                        if (event.peer.publicSigningKeyBytes.isNotEmpty()) {
                            val isUserApproved = pendingTrustedPeerIds.contains(peerId) ||
                                (peerRepository.getPeer(peerId)?.isTrusted == true)
                            peerRepository.saveOrUpdatePeer(
                                deviceId = peerId,
                                displayName = event.peer.displayName,
                                publicSigningKeyBytes = event.peer.publicSigningKeyBytes,
                                bluetoothAddress = event.peer.bluetoothAddress,
                                isTrusted = isUserApproved,
                            )
                            pendingTrustedPeerIds.remove(peerId)
                        }

                        val localId = _uiState.value.localIdentity?.id ?: ""
                        val convo = conversationRepository.getOrCreateDirectConversation(localId, peerId)
                        _navEvents.emit(
                            DiscoveryNavEvent.NavigateToChat(
                                conversationId = convo.id,
                                peerId = peerId,
                                peerDisplayName = event.peer.displayName
                            )
                        )
                    }
                    is TransportEvent.ChannelClosed -> {
                        val connected = _uiState.value.connectedPeerIds - event.peerId
                        _uiState.value = _uiState.value.copy(connectedPeerIds = connected)
                    }
                    is TransportEvent.ConnectionFailed -> {
                        pendingTrustedPeerIds.remove(event.peerId)
                        _uiState.value = _uiState.value.copy(
                            connectingPeerId = null,
                            errorMessage = "Failed to connect to peer: ${event.reason}"
                        )
                    }
                    is TransportEvent.TransportError -> {
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "Transport error: ${event.detail}"
                        )
                    }
                }
            }
        }
    }

    fun onPeerSelected(peer: PeerDevice) {
        viewModelScope.launch {
            // Check if already trusted in PeerRepository with a verified non-empty key
            val peerRecord = peerRepository.getPeer(peer.deviceId)
            if (peerRecord != null && peerRecord.isTrusted && peerRecord.publicSigningKeyBase64.isNotEmpty()) {
                // Already verified & trusted — initiate connection directly
                connectToPeer(peer)
            } else {
                // First contact or unverified key — show TOFU verification dialog
                _uiState.value = _uiState.value.copy(pendingTrustPeer = peer)
            }
        }
    }

    fun confirmTrust(peer: PeerDevice) {
        viewModelScope.launch {
            // Record user confirmation; the verified key will be bound upon successful handshake in ChannelOpened
            pendingTrustedPeerIds.add(peer.deviceId)
            _uiState.value = _uiState.value.copy(pendingTrustPeer = null)
            connectToPeer(peer)
        }
    }

    fun dismissTrustDialog() {
        val peerId = _uiState.value.pendingTrustPeer?.deviceId
        if (peerId != null) {
            pendingTrustedPeerIds.remove(peerId)
        }
        _uiState.value = _uiState.value.copy(pendingTrustPeer = null)
    }

    private fun connectToPeer(peer: PeerDevice) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(connectingPeerId = peer.deviceId)
            val res = transportManager.connect(peer)
            if (res is AppResult.Failure) {
                _uiState.value = _uiState.value.copy(
                    connectingPeerId = null,
                    errorMessage = "Connection error: ${res.error}"
                )
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
