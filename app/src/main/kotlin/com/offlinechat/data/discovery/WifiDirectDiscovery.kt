package com.offlinechat.data.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import com.offlinechat.domain.model.DiscoveryStatus
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiDirectDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceDiscovery {

    override val transportType: TransportType = TransportType.WIFI_DIRECT

    private val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = p2pManager?.initialize(context, context.mainLooper, null)

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    override val discoveredPeers: Flow<List<Peer>> = _discoveredPeers.asStateFlow()

    private val _discoveryStatus = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Idle)
    override val discoveryStatus: Flow<DiscoveryStatus> = _discoveryStatus.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: Flow<Boolean> = _isDiscovering.asStateFlow()

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery(localDisplayName: String, localDeviceId: String): Result<Unit> {
        val manager = p2pManager ?: return Result.failure(IllegalStateException("Wi-Fi Direct not supported"))
        val ch = channel ?: return Result.failure(IllegalStateException("Wi-Fi Direct channel initialization failed"))

        _isDiscovering.value = true

        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.requestPeers(ch) { peers: WifiP2pDeviceList? ->
                    val list = peers?.deviceList?.map { device: WifiP2pDevice ->
                        Peer(
                            deviceId = device.deviceAddress.replace(":", "").lowercase(),
                            displayName = device.deviceName ?: "Wi-Fi Peer",
                            bluetoothAddress = null,
                            transportType = TransportType.WIFI_DIRECT,
                            lastSeenAt = System.currentTimeMillis()
                        )
                    } ?: emptyList()
                    _discoveredPeers.value = list
                }
            }

            override fun onFailure(reasonCode: Int) {
                _isDiscovering.value = false
            }
        })

        return Result.success(Unit)
    }

    override suspend fun stopDiscovery() {
        _isDiscovering.value = false
        val manager = p2pManager ?: return
        val ch = channel ?: return
        manager.stopPeerDiscovery(ch, null)
    }
}
