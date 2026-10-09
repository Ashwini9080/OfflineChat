package com.offlinechat.presentation.chats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.data.discovery.BluetoothPermissionHelper
import com.offlinechat.domain.connection.ConnectionManager
import com.offlinechat.domain.model.DiscoveryStatus
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.usecase.ConnectPeerUseCase
import com.offlinechat.domain.usecase.DisconnectPeerUseCase
import com.offlinechat.domain.usecase.DiscoverPeersUseCase
import com.offlinechat.domain.usecase.GetLocalDeviceIdentityUseCase
import com.offlinechat.service.ConnectionForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

import com.offlinechat.data.discovery.WifiDirectPermissionHelper
import com.offlinechat.domain.model.TransportType

data class DiscoveryUiState(
    val isScanning: Boolean = false,
    val discoveryStatus: DiscoveryStatus = DiscoveryStatus.Idle,
    val peers: List<Peer> = emptyList(),
    val peerStates: Map<String, PeerConnectionState> = emptyMap(),
    val connectingPeerId: String? = null,
    val pendingTrustPeer: Peer? = null,
    val errorMessage: String? = null,
    val missingPermissions: List<String> = emptyList(),
    val isBluetoothDisabled: Boolean = false,
    val isBluetoothUnavailable: Boolean = false,
    val isWifiDisabled: Boolean = false,
    val isWifiDirectUnsupported: Boolean = false,
    val showPermissionRationale: Boolean = false,
    val isPermissionPermanentlyDenied: Boolean = false,
    val selectedTransportFilter: TransportType? = null
)

sealed interface DiscoveryNavigationEvent {
    data class OpenChat(
        val conversationId: String,
        val peerId: String,
        val peerDisplayName: String
    ) : DiscoveryNavigationEvent
}

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discoverPeersUseCase: DiscoverPeersUseCase,
    private val connectPeerUseCase: ConnectPeerUseCase,
    private val disconnectPeerUseCase: DisconnectPeerUseCase,
    private val connectionManager: ConnectionManager,
    private val conversationRepository: ConversationRepository,
    private val getLocalDeviceIdentityUseCase: GetLocalDeviceIdentityUseCase,
    val permissionHelper: BluetoothPermissionHelper,
    val wifiPermissionHelper: WifiDirectPermissionHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    private val _navEvents = MutableSharedFlow<DiscoveryNavigationEvent>()
    val navEvents: SharedFlow<DiscoveryNavigationEvent> = _navEvents.asSharedFlow()

    private val observedPeersJobs = ConcurrentHashMap<String, Job>()
    private var localDisplayName = ""
    private var localDeviceId = ""

    init {
        observePeers()
        observeDiscoveryStatus()
        observeIdentity()
    }

    private fun observeIdentity() {
        viewModelScope.launch {
            getLocalDeviceIdentityUseCase().collect { identity ->
                localDisplayName = identity.displayName
                localDeviceId = identity.deviceId
                if (permissionHelper.hasRequiredPermissions() && permissionHelper.isBluetoothEnabled()) {
                    startScan()
                } else if (!permissionHelper.isBluetoothEnabled()) {
                    _uiState.value = _uiState.value.copy(isBluetoothDisabled = true)
                } else if (!permissionHelper.hasRequiredPermissions()) {
                    _uiState.value = _uiState.value.copy(
                        missingPermissions = permissionHelper.getMissingPermissions(),
                        showPermissionRationale = true
                    )
                }
            }
        }
    }

    private fun observePeers() {
        viewModelScope.launch {
            discoverPeersUseCase.discoveredPeers.collect { list ->
                _uiState.value = _uiState.value.copy(peers = list)
                // Start observing connection states for newly discovered peers
                list.forEach { peer ->
                    if (!observedPeersJobs.containsKey(peer.deviceId)) {
                        observedPeersJobs[peer.deviceId] = launch {
                            connectionManager.observeConnectionState(peer.deviceId).collect { state ->
                                _uiState.value = _uiState.value.copy(
                                    peerStates = _uiState.value.peerStates + (peer.deviceId to state)
                                )
                            }
                        }
                    }
                }

                // Automatic Connection: Auto-connect to discovered peer if not connected/connecting
                autoConnectIfPossible(list)
            }
        }
        viewModelScope.launch {
            discoverPeersUseCase.isDiscovering.collect { isDiscovering ->
                _uiState.value = _uiState.value.copy(isScanning = isDiscovering)
            }
        }
    }

    private fun observeDiscoveryStatus() {
        viewModelScope.launch {
            discoverPeersUseCase.discoveryStatus.collect { status ->
                _uiState.value = when (status) {
                    is DiscoveryStatus.BluetoothDisabled -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            isBluetoothDisabled = true,
                            isScanning = false
                        )
                    }
                    is DiscoveryStatus.BluetoothUnavailable -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            isBluetoothUnavailable = true,
                            isScanning = false
                        )
                    }
                    is DiscoveryStatus.WifiDisabled -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            isWifiDisabled = true
                        )
                    }
                    is DiscoveryStatus.WifiDirectUnsupported -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            isWifiDirectUnsupported = true
                        )
                    }
                    is DiscoveryStatus.PermissionRequired -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            missingPermissions = status.permissions,
                            showPermissionRationale = true,
                            isScanning = false
                        )
                    }
                    is DiscoveryStatus.PermissionPermanentlyDenied -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            isPermissionPermanentlyDenied = true,
                            showPermissionRationale = true,
                            isScanning = false
                        )
                    }
                    is DiscoveryStatus.DiscoveryFailed -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            errorMessage = status.reason,
                            isScanning = false
                        )
                    }
                    is DiscoveryStatus.DiscoveryCancelled -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            isScanning = false
                        )
                    }
                    is DiscoveryStatus.DiscoveryComplete -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            isScanning = false
                        )
                    }
                    is DiscoveryStatus.Discovering -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            isScanning = true,
                            isBluetoothDisabled = false
                        )
                    }
                    is DiscoveryStatus.PermissionRevoked -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            missingPermissions = permissionHelper.getMissingPermissions(),
                            showPermissionRationale = true,
                            isScanning = false
                        )
                    }
                    is DiscoveryStatus.PermissionDenied -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            showPermissionRationale = true,
                            isScanning = false
                        )
                    }
                    is DiscoveryStatus.PermissionGranted -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            showPermissionRationale = false,
                            missingPermissions = emptyList()
                        )
                    }
                    is DiscoveryStatus.ReadyForDiscovery -> {
                        _uiState.value.copy(
                            discoveryStatus = status,
                            isBluetoothDisabled = false
                        )
                    }
                    else -> {
                        _uiState.value.copy(discoveryStatus = status)
                    }
                }
            }
        }
    }

    fun selectTransportFilter(filter: TransportType?) {
        _uiState.value = _uiState.value.copy(selectedTransportFilter = filter)
    }

    fun startScan() {
        val btSupported = permissionHelper.isBluetoothSupported()
        val btEnabled = permissionHelper.isBluetoothEnabled()
        val btPerms = permissionHelper.hasRequiredPermissions()

        val wifiSupported = wifiPermissionHelper.isWifiDirectSupported()
        val wifiEnabled = wifiPermissionHelper.isWifiEnabled()
        val wifiPerms = wifiPermissionHelper.hasRequiredPermissions()

        if (!btSupported && !wifiSupported) {
            _uiState.value = _uiState.value.copy(isBluetoothUnavailable = true, isWifiDirectUnsupported = true)
            return
        }

        if (!btEnabled && !wifiEnabled) {
            _uiState.value = _uiState.value.copy(isBluetoothDisabled = true, isWifiDisabled = true)
            return
        }

        val allMissing = (permissionHelper.getMissingPermissions() + wifiPermissionHelper.getMissingPermissions()).distinct()
        if (allMissing.isNotEmpty() && !btPerms && !wifiPerms) {
            _uiState.value = _uiState.value.copy(
                missingPermissions = allMissing,
                showPermissionRationale = true
            )
            return
        }

        viewModelScope.launch {
            connectionManager.startServerListener()
            val result = discoverPeersUseCase.startDiscovery(localDisplayName, localDeviceId)
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = result.exceptionOrNull()?.message ?: "Failed to start discovery"
                )
            }
        }
    }

    fun stopScan() {
        viewModelScope.launch {
            discoverPeersUseCase.stopDiscovery()
            _uiState.value = _uiState.value.copy(isScanning = false)
        }
    }

    fun onBluetoothEnabled() {
        _uiState.value = _uiState.value.copy(isBluetoothDisabled = false)
        connectionManager.startServerListener()
        startScan()
    }

    fun onWifiEnabled() {
        _uiState.value = _uiState.value.copy(isWifiDisabled = false)
        startScan()
    }

    fun onPermissionsGranted() {
        _uiState.value = _uiState.value.copy(
            showPermissionRationale = false,
            missingPermissions = emptyList(),
            isPermissionPermanentlyDenied = false
        )
        connectionManager.startServerListener()
        startScan()
    }

    fun onPermissionsDenied(permanentlyDenied: Boolean) {
        _uiState.value = _uiState.value.copy(
            showPermissionRationale = true,
            isPermissionPermanentlyDenied = permanentlyDenied,
            isScanning = false
        )
    }

    fun dismissPermissionRationale() {
        _uiState.value = _uiState.value.copy(showPermissionRationale = false)
    }

    fun dismissBluetoothDisabled() {
        _uiState.value = _uiState.value.copy(isBluetoothDisabled = false)
    }

    fun onConnectClicked(peer: Peer) {
        // Prevent concurrent multiple connection attempts to the same or conflicting peer
        if (_uiState.value.connectingPeerId != null) return
        connectToPeer(peer)
    }

    fun onCancelConnectClicked(peer: Peer) {
        viewModelScope.launch {
            connectionManager.cancelConnection(peer.deviceId)
            _uiState.value = _uiState.value.copy(
                connectingPeerId = null,
                peerStates = _uiState.value.peerStates + (peer.deviceId to PeerConnectionState.Disconnected)
            )
        }
    }

    fun onDisconnectClicked(peer: Peer) {
        viewModelScope.launch {
            disconnectPeerUseCase(peer.deviceId)
            _uiState.value = _uiState.value.copy(
                peerStates = _uiState.value.peerStates + (peer.deviceId to PeerConnectionState.Disconnected),
                peers = _uiState.value.peers.map {
                    if (it.deviceId == peer.deviceId) it.copy(isConnected = false) else it
                }
            )
        }
    }

    fun onOpenChatClicked(peer: Peer) {
        viewModelScope.launch {
            val conversation = conversationRepository.getOrCreateConversation(
                peerId = peer.deviceId,
                peerDisplayName = peer.displayName,
                transportType = peer.transportType
            )
            _navEvents.emit(
                DiscoveryNavigationEvent.OpenChat(
                    conversationId = conversation.id,
                    peerId = peer.deviceId,
                    peerDisplayName = peer.displayName
                )
            )
        }
    }

    fun confirmTrust(peer: Peer) {
        viewModelScope.launch {
            discoverPeersUseCase.trustPeer(peer)
            _uiState.value = _uiState.value.copy(pendingTrustPeer = null)
            connectToPeer(peer)
        }
    }

    fun dismissTrustDialog() {
        _uiState.value = _uiState.value.copy(pendingTrustPeer = null)
    }

    private var lastAutoConnectAttemptMs = 0L

    private fun autoConnectIfPossible(list: List<Peer>) {
        val now = System.currentTimeMillis()
        if (now - lastAutoConnectAttemptMs < 3000) return

        val currentState = _uiState.value
        if (currentState.connectingPeerId != null) return

        val isAnyConnected = currentState.peerStates.values.any { it is PeerConnectionState.Connected } ||
                currentState.peers.any { it.isConnected } ||
                list.any { it.isConnected }
        if (isAnyConnected) return

        val candidate = list.firstOrNull { peer ->
            val state = currentState.peerStates[peer.deviceId]
            !peer.isConnected && state !is PeerConnectionState.Connected && state !is PeerConnectionState.Connecting
        } ?: return

        lastAutoConnectAttemptMs = now
        Log.i("DiscoveryViewModel", "Auto-connecting to discovered peer: ${candidate.displayName} [${candidate.deviceId}]")
        connectToPeer(candidate)
    }

    private fun connectToPeer(peer: Peer) {
        if (_uiState.value.connectingPeerId == peer.deviceId) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                connectingPeerId = peer.deviceId,
                peerStates = _uiState.value.peerStates + (peer.deviceId to PeerConnectionState.Connecting)
            )

            val result = connectPeerUseCase(peer)
            _uiState.value = _uiState.value.copy(connectingPeerId = null)

            if (result.isSuccess) {
                ConnectionForegroundService.startService(context, peer.displayName)
                _uiState.value = _uiState.value.copy(
                    peers = _uiState.value.peers.map {
                        if (it.deviceId == peer.deviceId) it.copy(isConnected = true) else it
                    },
                    peerStates = _uiState.value.peerStates + (peer.deviceId to PeerConnectionState.Connected)
                )
            } else {
                val failureMsg = result.exceptionOrNull()?.message ?: "Failed to connect to peer"
                Log.w("DiscoveryViewModel", "Connection to ${peer.displayName} failed: $failureMsg. Scheduling auto-retry in 2s...")
                _uiState.value = _uiState.value.copy(
                    errorMessage = failureMsg,
                    peerStates = _uiState.value.peerStates + (peer.deviceId to PeerConnectionState.ConnectionFailed(failureMsg))
                )
                // Auto-retry connection after 2 seconds
                launch {
                    delay(2000)
                    val stillDisconnected = !connectionManager.isConnected(peer.deviceId) &&
                            _uiState.value.connectingPeerId == null &&
                            !_uiState.value.peers.any { it.isConnected }
                    if (stillDisconnected) {
                        Log.i("DiscoveryViewModel", "Auto-retrying connection to ${peer.displayName}...")
                        connectToPeer(peer)
                    }
                }
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        observedPeersJobs.values.forEach { it.cancel() }
        observedPeersJobs.clear()
        viewModelScope.launch {
            discoverPeersUseCase.stopDiscovery()
        }
    }
}
