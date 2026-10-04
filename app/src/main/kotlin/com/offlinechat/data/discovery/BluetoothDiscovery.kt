package com.offlinechat.data.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.offlinechat.domain.model.DiscoveryStatus
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: BluetoothPermissionHelper,
    private val peerDeduplicator: PeerDeduplicator,
    private val deviceMapper: DeviceMapper,
    private val errorMapper: BluetoothErrorMapper
) : DeviceDiscovery {

    companion object {
        private const val TAG = "BluetoothDiscovery"
        val SERVICE_UUID: UUID = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb")
    }

    override val transportType: TransportType = TransportType.BLUETOOTH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    override val discoveredPeers: Flow<List<Peer>> = _discoveredPeers.asStateFlow()

    private val _discoveryStatus = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Idle)
    override val discoveryStatus: Flow<DiscoveryStatus> = _discoveryStatus.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: Flow<Boolean> = _isDiscovering.asStateFlow()

    private var isReceiverRegistered = false
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    // ── Classic Bluetooth BroadcastReceiver ──────────────────────────────────
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    if (device != null) {
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        val extraName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)

                        val peer = deviceMapper.mapClassicDevice(device, extraName, rssi)
                        Log.d(TAG, "Classic Inquiry Found: ${peer.displayName} [${peer.bluetoothAddress}], RSSI: ${peer.rssi} dBm")
                        handleDiscoveredPeer(peer)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    Log.d(TAG, "Bluetooth Classic Discovery Started")
                    _discoveryStatus.value = DiscoveryStatus.Discovering
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Bluetooth Classic Discovery Finished")
                    if (_isDiscovering.value) {
                        _discoveryStatus.value = DiscoveryStatus.DiscoveryComplete
                        _isDiscovering.value = false
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            Log.w(TAG, "Bluetooth turned off by user during discovery")
                            _discoveryStatus.value = DiscoveryStatus.BluetoothDisabled
                            scope.launch { stopDiscovery() }
                        }
                        BluetoothAdapter.STATE_ON -> {
                            Log.d(TAG, "Bluetooth turned on by user")
                            _discoveryStatus.value = DiscoveryStatus.ReadyForDiscovery
                        }
                    }
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery(localDisplayName: String, localDeviceId: String): Result<Unit> {
        return mutex.withLock {
            // 1. Hardware availability check
            _discoveryStatus.value = DiscoveryStatus.CheckingBluetooth
            if (!permissionHelper.isBluetoothSupported()) {
                Log.e(TAG, "Bluetooth is not supported on this device")
                _discoveryStatus.value = DiscoveryStatus.BluetoothUnavailable
                return Result.failure(IllegalStateException("Bluetooth hardware not available"))
            }

            // 2. Bluetooth enabled check
            if (!permissionHelper.isBluetoothEnabled()) {
                Log.w(TAG, "Bluetooth is turned off")
                _discoveryStatus.value = DiscoveryStatus.BluetoothDisabled
                return Result.failure(IllegalStateException("Bluetooth is disabled. Please enable Bluetooth."))
            }

            // 3. Permission checks
            _discoveryStatus.value = DiscoveryStatus.CheckingPermissions
            if (!permissionHelper.hasRequiredPermissions()) {
                val missing = permissionHelper.getMissingPermissions()
                Log.w(TAG, "Missing Bluetooth permissions: $missing")
                _discoveryStatus.value = DiscoveryStatus.PermissionRequired(missing)
                return Result.failure(SecurityException("Missing required permissions: $missing"))
            }

            _discoveryStatus.value = DiscoveryStatus.StartingDiscovery
            _isDiscovering.value = true

            val adapter = permissionHelper.bluetoothAdapter
                ?: return Result.failure(IllegalStateException("BluetoothAdapter unavailable"))

            try {
                // 4. Register Classic Bluetooth BroadcastReceiver
                registerReceiverSafely()

                // 5. Start Classic Bluetooth Discovery (Inquiry)
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }
                val started = adapter.startDiscovery()
                Log.d(TAG, "adapter.startDiscovery() returned: $started")
                if (!started) {
                    Log.w(TAG, "Classic discovery inquiry could not be initiated")
                }

                // 6. Start BLE Advertising (Broadcast Presence)
                startBleAdvertising(adapter, localDisplayName, localDeviceId)

                // 7. Start BLE Scanning (Detect other OfflineChat peers)
                startBleScanning(adapter)

                _discoveryStatus.value = DiscoveryStatus.Discovering
                Log.d(TAG, "Dual-mode Bluetooth discovery successfully initiated")
                Result.success(Unit)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException during discovery start - permission likely revoked", e)
                _discoveryStatus.value = DiscoveryStatus.PermissionRevoked
                _isDiscovering.value = false
                unregisterReceiverSafely()
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during discovery start", e)
                val msg = errorMapper.mapException(e)
                _discoveryStatus.value = DiscoveryStatus.DiscoveryFailed(msg)
                _isDiscovering.value = false
                unregisterReceiverSafely()
                Result.failure(e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        mutex.withLock {
            if (!_isDiscovering.value && _discoveryStatus.value is DiscoveryStatus.Idle) {
                return
            }

            Log.d(TAG, "Stopping all Bluetooth discovery routines")
            _isDiscovering.value = false

            // Stop Classic Discovery
            try {
                val adapter = permissionHelper.bluetoothAdapter
                if (adapter?.isDiscovering == true) {
                    adapter.cancelDiscovery()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling classic discovery", e)
            }

            // Unregister BroadcastReceiver
            unregisterReceiverSafely()

            // Stop BLE Advertising
            try {
                val advertiser = permissionHelper.bluetoothAdapter?.bluetoothLeAdvertiser
                advertiseCallback?.let { advertiser?.stopAdvertising(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping BLE advertising", e)
            }
            advertiseCallback = null

            // Stop BLE Scanning
            try {
                val scanner = permissionHelper.bluetoothAdapter?.bluetoothLeScanner
                scanCallback?.let { scanner?.stopScan(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping BLE scanner", e)
            }
            scanCallback = null

            _discoveryStatus.value = DiscoveryStatus.DiscoveryCancelled
        }
    }

    // ── Internal Helpers ──────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startBleAdvertising(adapter: BluetoothAdapter, displayName: String, deviceId: String) {
        try {
            val advertiser = adapter.bluetoothLeAdvertiser ?: run {
                Log.w(TAG, "BluetoothLeAdvertiser is unavailable on this hardware")
                return
            }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()

            val serviceParcelUuid = ParcelUuid(SERVICE_UUID)
            val serviceData = deviceId.take(8).toByteArray(StandardCharsets.UTF_8)

            // Primary advertisement data: compact to avoid ADVERTISE_FAILED_DATA_TOO_LARGE (31-byte limit)
            val data = AdvertiseData.Builder()
                .addServiceUuid(serviceParcelUuid)
                .addServiceData(serviceParcelUuid, serviceData)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()

            // Scan response: provides device name when requested by active scanners
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.d(TAG, "BLE Advertising started successfully for '$displayName' [ID: ${deviceId.take(8)}]")
                }
                override fun onStartFailure(errorCode: Int) {
                    val errorMsg = errorMapper.mapAdvertiseError(errorCode)
                    Log.w(TAG, "BLE Advertising failed: $errorMsg (code $errorCode)")
                }
            }
            advertiseCallback = callback
            advertiser.startAdvertising(settings, data, scanResponse, callback)
        } catch (e: Exception) {
            val msg = errorMapper.mapException(e)
            Log.w(TAG, "BLE Advertising initialization error: $msg", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScanning(adapter: BluetoothAdapter) {
        try {
            val scanner = adapter.bluetoothLeScanner ?: run {
                Log.w(TAG, "BluetoothLeScanner is unavailable on this hardware")
                return
            }
            val scanFilters = listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            )
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.let { handleBleScanResult(it) }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.forEach { handleBleScanResult(it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    val errorMsg = errorMapper.mapScanError(errorCode)
                    Log.w(TAG, "BLE Scan failed: $errorMsg (code $errorCode)")
                    _discoveryStatus.value = DiscoveryStatus.DiscoveryFailed(errorMsg)
                }
            }
            scanCallback = callback
            scanner.startScan(scanFilters, scanSettings, callback)
            Log.d(TAG, "BLE Scanning started for service UUID: $SERVICE_UUID")
        } catch (e: Exception) {
            val msg = errorMapper.mapException(e)
            Log.w(TAG, "BLE Scanner initialization failed: $msg", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBleScanResult(result: ScanResult) {
        val peer = deviceMapper.mapBleScanResult(result, SERVICE_UUID) ?: return
        Log.d(TAG, "BLE Peer Detected: ${peer.displayName} [${peer.bluetoothAddress}], RSSI: ${peer.rssi} dBm")
        handleDiscoveredPeer(peer)
    }

    /**
     * Prevents duplicates, updates last seen timestamp & RSSI, and keeps UI sorted and stable.
     */
    private fun handleDiscoveredPeer(peer: Peer) {
        scope.launch {
            val updated = peerDeduplicator.updatePeerList(
                currentList = _discoveredPeers.value,
                deviceId = peer.deviceId,
                displayName = peer.displayName,
                bluetoothAddress = peer.bluetoothAddress,
                rssi = peer.rssi,
                transportType = peer.transportType,
                timestamp = peer.lastSeenAt
            )
            _discoveredPeers.value = updated
            _discoveryStatus.value = DiscoveryStatus.DeviceFound(updated.size)
        }
    }

    private fun registerReceiverSafely() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            try {
                context.registerReceiver(discoveryReceiver, filter)
                isReceiverRegistered = true
                Log.d(TAG, "Registered Bluetooth discovery broadcast receiver")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register discovery receiver", e)
            }
        }
    }

    private fun unregisterReceiverSafely() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
                Log.d(TAG, "Unregistered Bluetooth discovery broadcast receiver")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering discovery receiver", e)
            } finally {
                isReceiverRegistered = false
            }
        }
    }
}
