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
import com.offlinechat.data.transport.framing.RfcommFrameCodec
import com.offlinechat.domain.connection.PeerConnection
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete [PeerConnection] implementation using Android Bluetooth Classic RFCOMM (SPP).
 *
 * Implements length-prefixed bidirectional framing, non-blocking asynchronous read loop,
 * serialized atomic writes, link-loss detection, timeout protection, and clean resource cleanup.
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

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    private var activeSocket: BluetoothSocket? = null
    private var frameReaderJob: Job? = null
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
     * BroadcastReceiver to monitor local Bluetooth radio state changes.
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

    /**
     * Attaches an already-connected socket (e.g., from an accepted server socket connection)
     */
    fun attachSocket(socket: BluetoothSocket, displayName: String) {
        activeSocket = socket
        isIntentionalDisconnect.set(false)
        _connectionState.value = PeerConnectionState.Connected
        startFrameReader(socket, displayName)
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
        val remoteDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote device for address: $address", e)
            return@withContext Result.failure(e)
        }
        Log.d(TAG, "Target device: ${remoteDevice.name ?: displayName}, bondState: ${remoteDevice.bondState}")

        // Cancel active inquiry scan to avoid radio congestion and packet collisions
        if (adapter.isDiscovering) {
            Log.d(TAG, "Cancelling active discovery inquiry prior to socket connect")
            try { adapter.cancelDiscovery() } catch (_: Exception) {}
        }

        val strategies: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "Insecure SPP" to { remoteDevice.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "Secure SPP" to { remoteDevice.createRfcommSocketToServiceRecord(SPP_UUID) },
            "Insecure APP_UUID" to { remoteDevice.createInsecureRfcommSocketToServiceRecord(APP_UUID) },
            "Secure APP_UUID" to { remoteDevice.createRfcommSocketToServiceRecord(APP_UUID) },
            "Reflection Channel 1" to {
                val method = remoteDevice.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(remoteDevice, 1) as BluetoothSocket
            }
        )

        var lastException: Exception? = null
        for ((name, factory) in strategies) {
            var candidateSocket: BluetoothSocket? = null
            try {
                Log.d(TAG, "Attempting Bluetooth connect via strategy '$name' to $address...")
                candidateSocket = factory()
                candidateSocket.connect()
                Log.i(TAG, "RFCOMM socket connected successfully via '$name' to $displayName [$address]")

                activeSocket = candidateSocket
                _connectionState.value = PeerConnectionState.Connected

                // Launch frame reader loop
                startFrameReader(candidateSocket, displayName)
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Connection via '$name' to $address failed: ${e.message}")
                lastException = e
                try { candidateSocket?.close() } catch (_: Exception) {}
            }
        }

        Result.failure(lastException ?: IOException("Failed to establish Bluetooth connection to $address"))
    }

    /**
     * Continuous background loop reading length-prefixed message frames from the socket.
     * Defragments frames and handles partial/multi reads cleanly.
     */
    private fun startFrameReader(socket: BluetoothSocket, displayName: String) {
        frameReaderJob?.cancel()
        frameReaderJob = scope.launch(ioDispatcher) {
            try {
                val input = socket.inputStream
                while (socket.isConnected && !isIntentionalDisconnect.get()) {
                    try {
                        val frameData = RfcommFrameCodec.readFrame(input)
                        Log.d(TAG, "Decoded frame (${frameData.size} bytes) from $displayName [$peerId]")
                        _incomingFrames.emit(frameData)
                    } catch (e: EOFException) {
                        Log.i(TAG, "Remote peer closed connection (EOF) for $displayName [$peerId]")
                        break
                    } catch (e: IOException) {
                        if (!isIntentionalDisconnect.get()) {
                            Log.w(TAG, "IOException reading frame from $displayName [$peerId]: ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: CancellationException) {
                // Job cancelled normally
            } finally {
                if (!isIntentionalDisconnect.get() && _connectionState.value is PeerConnectionState.Connected) {
                    Log.w(TAG, "Reporting ConnectionLost for peer: $displayName")
                    _connectionState.value = PeerConnectionState.ConnectionLost("Remote device disconnected or link dropped")
                    cleanupSocket(notifyDisconnected = true)
                }
            }
        }
    }

    /**
     * Atomically transmits a length-prefixed frame to the remote peer.
     * Synchronised on the output stream so concurrent sends are never interleaved.
     */
    suspend fun sendFrame(data: ByteArray): Result<Unit> = withContext(ioDispatcher) {
        val socket = activeSocket
            ?: return@withContext Result.failure(IllegalStateException("No active socket for peer $peerId"))

        if (!socket.isConnected) {
            return@withContext Result.failure(IllegalStateException("Socket is disconnected for peer $peerId"))
        }

        try {
            val out = socket.outputStream
            RfcommFrameCodec.writeFrame(out, data)
            Log.d(TAG, "Successfully sent frame (${data.size} bytes) to $peerId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write frame to peer $peerId", e)
            if (!isIntentionalDisconnect.get()) {
                _connectionState.value = PeerConnectionState.ConnectionLost("Write failure: ${e.message}")
                cleanupSocket(notifyDisconnected = true)
            }
            Result.failure(e)
        }
    }

    override suspend fun disconnect() = withContext(ioDispatcher) {
        Log.i(TAG, "Disconnect requested for peer: $peerId")
        isIntentionalDisconnect.set(true)
        _connectionState.value = PeerConnectionState.Disconnecting
        cleanupSocket(notifyDisconnected = true)
    }

    private fun cleanupSocket(notifyDisconnected: Boolean) {
        frameReaderJob?.cancel()
        frameReaderJob = null

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
