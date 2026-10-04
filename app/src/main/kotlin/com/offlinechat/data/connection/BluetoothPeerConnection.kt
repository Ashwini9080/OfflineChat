package com.offlinechat.data.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.offlinechat.domain.connection.PeerConnection
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete [PeerConnection] implementation using Android Bluetooth Classic RFCOMM (SPP).
 *
 * Implements full connection lifecycle monitoring, pairing awareness, connection timeout,
 * link-loss detection, Bluetooth state monitoring, and leak-free resource disposal.
 */
class BluetoothPeerConnection(
    override val peerId: String,
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PeerConnection {

    companion object {
        private const val TAG = "BluetoothPeerConn"

        // Standard Serial Port Profile (SPP) UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Secondary custom RFCOMM UUID fallback
        val APP_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")

        const val CONNECTION_TIMEOUT_MS = 15_000L
    }

    private val _connectionState = MutableStateFlow<PeerConnectionState>(PeerConnectionState.Idle)
    override val connectionState: StateFlow<PeerConnectionState> = _connectionState.asStateFlow()

    private var activeSocket: BluetoothSocket? = null
    private var monitorJob: Job? = null
    private var isIntentionalDisconnect = AtomicBoolean(false)
    private var receiverRegistered = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = activeSocket?.isConnected == true && _connectionState.value is PeerConnectionState.Connected

    val socket: BluetoothSocket?
        get() = activeSocket

    val inputStream: InputStream?
        get() = activeSocket?.inputStream

    val outputStream: OutputStream?
        get() = activeSocket?.outputStream

    /**
     * BroadcastReceiver to monitor local Bluetooth radio state changes and pairing state changes.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        Log.w(TAG, "Bluetooth turned off while peer connection active [$peerId]")
                        scope.launch {
                            _connectionState.value = PeerConnectionState.BluetoothDisabled
                            cleanupSocket(notifyDisconnected = true)
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    Log.d(TAG, "Bond state changed for ${device?.address}: $prevBondState -> $bondState")
                }
            }
        }
    }

    init {
        registerReceivers()
    }

    private fun registerReceivers() {
        if (!receiverRegistered.getAndSet(true)) {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            try {
                context.registerReceiver(bluetoothStateReceiver, filter)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register Bluetooth state receiver", e)
                receiverRegistered.set(false)
            }
        }
    }

    private fun unregisterReceivers() {
        if (receiverRegistered.getAndSet(false)) {
            try {
                context.unregisterReceiver(bluetoothStateReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister Bluetooth state receiver", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: Peer): Result<Unit> = withContext(ioDispatcher) {
        val adapter = bluetoothAdapter
            ?: run {
                _connectionState.value = PeerConnectionState.ConnectionFailed("Bluetooth hardware unavailable")
                return@withContext Result.failure(IllegalStateException("Bluetooth hardware unavailable"))
            }

        if (!adapter.isEnabled) {
            _connectionState.value = PeerConnectionState.BluetoothDisabled
            return@withContext Result.failure(IllegalStateException("Bluetooth is disabled"))
        }

        val address = peer.bluetoothAddress
            ?: run {
                _connectionState.value = PeerConnectionState.ConnectionFailed("No Bluetooth hardware address available")
                return@withContext Result.failure(IllegalArgumentException("No Bluetooth address for peer ${peer.displayName}"))
            }

        // Check if already connected
        activeSocket?.let { existing ->
            if (existing.isConnected) {
                _connectionState.value = PeerConnectionState.Connected
                return@withContext Result.success(Unit)
            }
        }

        isIntentionalDisconnect.set(false)
        _connectionState.value = PeerConnectionState.Connecting
        Log.i(TAG, "Initiating Bluetooth RFCOMM connection to ${peer.displayName} [$address]...")

        val connectionResult: Result<Unit> = try {
            val connectedWithinTimeout = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                establishRfcommSocket(adapter, address, peer.displayName)
            }

            if (connectedWithinTimeout == null) {
                Log.w(TAG, "Connection attempt to ${peer.displayName} [$address] timed out after ${CONNECTION_TIMEOUT_MS}ms")
                cleanupSocket(notifyDisconnected = false)
                _connectionState.value = PeerConnectionState.ConnectionTimeout(CONNECTION_TIMEOUT_MS)
                // Transition to ConnectionFailed after timeout per spec
                _connectionState.value = PeerConnectionState.ConnectionFailed("Connection timed out after 15s")
                Result.failure(IOException("Connection timed out after 15 seconds"))
            } else {
                connectedWithinTimeout
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Connection attempt to ${peer.displayName} cancelled by caller")
            cleanupSocket(notifyDisconnected = true)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ${peer.displayName} [$address]", e)
            cleanupSocket(notifyDisconnected = false)
            val isRejection = e.message?.contains("refused", ignoreCase = true) == true ||
                    e.message?.contains("closed", ignoreCase = true) == true
            if (isRejection) {
                _connectionState.value = PeerConnectionState.ConnectionRejected(e.message ?: "Connection refused by peer")
            } else {
                _connectionState.value = PeerConnectionState.ConnectionFailed(e.message ?: "Connection failed")
            }
            Result.failure(e)
        }

        connectionResult
    }

    @SuppressLint("MissingPermission")
    private suspend fun establishRfcommSocket(
        adapter: BluetoothAdapter,
        address: String,
        displayName: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val remoteDevice = adapter.getRemoteDevice(address)
        Log.d(TAG, "Target device: ${remoteDevice.name ?: displayName}, bondState: ${remoteDevice.bondState}")

        // Cancel active inquiry scan to avoid radio congestion and packet collisions
        if (adapter.isDiscovering) {
            Log.d(TAG, "Cancelling active discovery inquiry prior to socket connect")
            adapter.cancelDiscovery()
        }

        // Socket Creation Chain:
        // 1. Insecure RFCOMM SPP (fast, pinless P2P pairing)
        // 2. Secure RFCOMM SPP (standard Android authenticated pairing)
        // 3. Custom App UUID
        // 4. Reflection Channel 1 fallback
        val socket = createSocketWithFallback(remoteDevice)
            ?: return@withContext Result.failure(IOException("Failed to create RFCOMM socket to $address"))

        Log.d(TAG, "Connecting RFCOMM socket to $address...")
        socket.connect()
        Log.i(TAG, "RFCOMM socket connected successfully to $displayName [$address]")

        activeSocket = socket
        _connectionState.value = PeerConnectionState.Connected

        // Start background connection loss monitor
        startConnectionLossMonitor(socket, displayName)

        Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    private fun createSocketWithFallback(device: BluetoothDevice): BluetoothSocket? {
        // Attempt 1: Insecure SPP
        try {
            return device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "createInsecureRfcommSocketToServiceRecord failed, trying secure", e)
        }

        // Attempt 2: Secure SPP
        try {
            return device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "createRfcommSocketToServiceRecord failed, trying APP_UUID", e)
        }

        // Attempt 3: APP_UUID Insecure
        try {
            return device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "createInsecureRfcommSocketToServiceRecord(APP_UUID) failed, trying reflection", e)
        }

        // Attempt 4: Direct Channel 1 reflection (well known workaround for non-standard OEM stacks)
        try {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            return method.invoke(device, 1) as BluetoothSocket
        } catch (e: Exception) {
            Log.e(TAG, "Reflection createRfcommSocket failed", e)
        }

        return null
    }

    /**
     * Monitors the connected socket input stream for connection loss / EOF / unexpected closure.
     */
    private fun startConnectionLossMonitor(socket: BluetoothSocket, displayName: String) {
        monitorJob?.cancel()
        monitorJob = scope.launch(ioDispatcher) {
            try {
                val input = socket.inputStream
                // We monitor the stream. Even without Phase 5 data, reading blocks until remote closes (returns -1)
                // or socket drops (throws IOException).
                val buffer = ByteArray(1)
                while (socket.isConnected && !isIntentionalDisconnect.get()) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        Log.i(TAG, "Remote end closed socket stream (EOF) for $displayName [$peerId]")
                        break
                    }
                    // In Phase 4, we only establish connection; bytes received (if any) are kept or discarded
                }
            } catch (e: IOException) {
                if (!isIntentionalDisconnect.get()) {
                    Log.w(TAG, "Connection lost unexpectedly to $displayName [$peerId]: ${e.message}")
                }
            } catch (e: CancellationException) {
                // Job was cancelled normally
            } finally {
                if (!isIntentionalDisconnect.get() && _connectionState.value is PeerConnectionState.Connected) {
                    Log.w(TAG, "Reporting ConnectionLost for peer: $displayName")
                    _connectionState.value = PeerConnectionState.ConnectionLost("Remote device disconnected or link dropped")
                    cleanupSocket(notifyDisconnected = true)
                }
            }
        }
    }

    override suspend fun disconnect() = withContext(ioDispatcher) {
        Log.i(TAG, "Disconnect requested for peer: $peerId")
        isIntentionalDisconnect.set(true)
        _connectionState.value = PeerConnectionState.Disconnecting
        cleanupSocket(notifyDisconnected = true)
    }

    private fun cleanupSocket(notifyDisconnected: Boolean) {
        monitorJob?.cancel()
        monitorJob = null

        val socket = activeSocket
        activeSocket = null

        if (socket != null) {
            try {
                socket.close()
                Log.d(TAG, "Closed Bluetooth socket for $peerId")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing Bluetooth socket for $peerId", e)
            }
        }

        if (notifyDisconnected) {
            _connectionState.value = PeerConnectionState.Disconnected
        }
    }

    override fun release() {
        Log.d(TAG, "Releasing BluetoothPeerConnection resources for $peerId")
        isIntentionalDisconnect.set(true)
        unregisterReceivers()
        cleanupSocket(notifyDisconnected = true)
    }
}
