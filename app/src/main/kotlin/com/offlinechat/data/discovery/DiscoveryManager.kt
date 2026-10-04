package com.offlinechat.data.discovery

import com.offlinechat.domain.model.DiscoveryStatus
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscoveryManager @Inject constructor(
    private val bluetoothDiscovery: BluetoothDiscovery,
    private val wifiDirectDiscovery: WifiDirectDiscovery
) : DeviceDiscovery {

    override val transportType: TransportType = TransportType.BLUETOOTH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    override val discoveredPeers: Flow<List<Peer>> = _discoveredPeers.asStateFlow()

    override val discoveryStatus: Flow<DiscoveryStatus> = combine(
        bluetoothDiscovery.discoveryStatus,
        wifiDirectDiscovery.discoveryStatus
    ) { btStatus, wifiStatus ->
        when {
            wifiStatus is DiscoveryStatus.DeviceFound -> wifiStatus
            btStatus is DiscoveryStatus.DeviceFound -> btStatus
            wifiStatus is DiscoveryStatus.Discovering -> wifiStatus
            btStatus is DiscoveryStatus.Discovering -> btStatus
            wifiStatus is DiscoveryStatus.StartingDiscovery -> wifiStatus
            btStatus is DiscoveryStatus.StartingDiscovery -> btStatus
            wifiStatus is DiscoveryStatus.WifiDisabled -> wifiStatus
            btStatus is DiscoveryStatus.BluetoothDisabled -> btStatus
            wifiStatus !is DiscoveryStatus.Idle -> wifiStatus
            else -> btStatus
        }
    }

    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: Flow<Boolean> = _isDiscovering.asStateFlow()

    init {
        scope.launch {
            combine(
                bluetoothDiscovery.discoveredPeers,
                wifiDirectDiscovery.discoveredPeers
            ) { btPeers, wifiPeers ->
                (btPeers + wifiPeers).distinctBy { it.deviceId }
            }.collect { merged ->
                _discoveredPeers.value = merged
            }
        }

        scope.launch {
            combine(
                bluetoothDiscovery.isDiscovering,
                wifiDirectDiscovery.isDiscovering
            ) { btScanning, wifiScanning ->
                btScanning || wifiScanning
            }.collect { scanning ->
                _isDiscovering.value = scanning
            }
        }
    }

    override suspend fun startDiscovery(localDisplayName: String, localDeviceId: String): Result<Unit> {
        val btResult = bluetoothDiscovery.startDiscovery(localDisplayName, localDeviceId)
        val wifiResult = wifiDirectDiscovery.startDiscovery(localDisplayName, localDeviceId)
        return if (btResult.isSuccess || wifiResult.isSuccess) {
            Result.success(Unit)
        } else {
            btResult
        }
    }

    override suspend fun stopDiscovery() {
        bluetoothDiscovery.stopDiscovery()
        wifiDirectDiscovery.stopDiscovery()
    }
}
