package com.offlinechat.data.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.offlinechat.domain.connection.ConnectionManager
import com.offlinechat.domain.connection.PeerConnection
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton implementation of [ConnectionManager] coordinating Bluetooth RFCOMM connections.
 *
 * Responsibilities:
 * - Starting outgoing Bluetooth connections
 * - Listening for incoming Bluetooth connections (so Phone A <-> Phone B both establish connected state)
 * - Enforcing connection ownership and preventing duplicate/conflicting connection attempts
 * - Providing a unified, reactive [PeerConnectionState] stream per peer
 * - Cancelling in-flight connections
 * - Disconnecting and releasing all resources
 * - Caching non-sensitive peer metadata for future reconnection
 */
@Singleton
class BluetoothConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ConnectionManager {

    companion object {
        private const val TAG = "BtConnectionManager"
        private const val SERVER_SERVICE_NAME = "OfflineChatBluetoothP2P"
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val connectionMutex = Mutex()

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    // Active connection wrappers keyed by peerId (or Bluetooth address)
    private val activeConnections = ConcurrentHashMap<String, PeerConnection>()

    // Observable states keyed by peerId
    private val connectionStates = ConcurrentHashMap<String, MutableStateFlow<PeerConnectionState>>()

    // In-flight connection jobs
    private val connectionJobs = ConcurrentHashMap<String, Job>()

    // Reconnection metadata cache: non-sensitive identification info
    private val lastKnownPeers = ConcurrentHashMap<String, Peer>()

    // Server socket listening for incoming RFCOMM peer connections
    private var serverSocket: BluetoothServerSocket? = null
    private val isListening = AtomicBoolean(false)
    private var serverListenJob: Job? = null

    init {
        startServerListener()
    }

    override fun observeConnectionState(peerId: String): Flow<PeerConnectionState> {
        return getOrCreateStateFlow(peerId).asStateFlow()
    }

    override fun getConnectionState(peerId: String): PeerConnectionState {
        return getOrCreateStateFlow(peerId).value
    }

    override fun isConnected(peerId: String): Boolean {
        return activeConnections[peerId]?.isConnected == true ||
                connectionStates[peerId]?.value is PeerConnectionState.Connected
    }

    override fun getActiveConnection(peerId: String): PeerConnection? {
        return activeConnections[peerId]
    }

    private fun getOrCreateStateFlow(peerId: String): MutableStateFlow<PeerConnectionState> {
        return connectionStates.getOrPut(peerId) {
            MutableStateFlow(PeerConnectionState.Idle)
        }
    }

    /**
     * Starts listening for incoming RFCOMM connections from nearby compatible peers.
     */
    @SuppressLint("MissingPermission")
    fun startServerListener() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || isListening.get()) return

        isListening.set(true)
        serverListenJob?.cancel()
        serverListenJob = scope.launch(ioDispatcher) {
            try {
                // Insecure RFCOMM listener for streamlined pairing
                val server = try {
                    adapter.listenUsingInsecureRfcommWithServiceRecord(
                        SERVER_SERVICE_NAME,
                        BluetoothPeerConnection.SPP_UUID
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Insecure RFCOMM listen failed, falling back to secure", e)
                    adapter.listenUsingRfcommWithServiceRecord(
                        SERVER_SERVICE_NAME,
                        BluetoothPeerConnection.SPP_UUID
                    )
                }

                serverSocket = server
                Log.i(TAG, "RFCOMM server socket listening on UUID: ${BluetoothPeerConnection.SPP_UUID}")

                while (isListening.get()) {
                    val socket: BluetoothSocket = try {
                        server.accept()
                    } catch (e: IOException) {
                        Log.d(TAG, "Server socket accept completed or closed: ${e.message}")
                        break
                    }

                    handleIncomingConnection(socket)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server socket encountered exception: ${e.message}")
            } finally {
                isListening.set(false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleIncomingConnection(socket: BluetoothSocket) {
        val remoteDevice: BluetoothDevice? = socket.remoteDevice
        val address = remoteDevice?.address ?: "UNKNOWN"
        val name = remoteDevice?.name ?: "Nearby Device"
        val peerId = address // Use address as deterministic device identifier if not yet exchanged

        Log.i(TAG, "Incoming Bluetooth RFCOMM connection accepted from: $name [$address]")

        val incomingPeer = Peer(
            deviceId = peerId,
            displayName = name,
            bluetoothAddress = address,
            isConnected = true,
            transportType = TransportType.BLUETOOTH,
            lastSeenAt = System.currentTimeMillis()
        )
        lastKnownPeers[peerId] = incomingPeer

        val peerConnection = BluetoothPeerConnection(
            peerId = peerId,
            context = context,
            bluetoothAdapter = bluetoothAdapter,
            scope = scope,
            ioDispatcher = ioDispatcher
        )

        // Close any prior connection for this peer
        activeConnections.remove(peerId)?.release()
        activeConnections[peerId] = peerConnection

        val stateFlow = getOrCreateStateFlow(peerId)
        stateFlow.value = PeerConnectionState.Connected

        // Forward connection states
        scope.launch {
            peerConnection.connectionState.collect { state ->
                stateFlow.value = state
                if (state is PeerConnectionState.Disconnected || state is PeerConnectionState.ConnectionLost) {
                    activeConnections.remove(peerId)
                }
            }
        }
    }

    /**
     * Connects to a target peer.
     * Prevents duplicate/conflicting connection attempts (Section 6).
     */
    override suspend fun connect(peer: Peer): Result<Unit> {
        connectionMutex.withLock {
            val currentState = getConnectionState(peer.deviceId)

            // Prevent duplicate connection attempts
            if (currentState is PeerConnectionState.Connecting) {
                Log.w(TAG, "Connection attempt already active for peer: ${peer.displayName} [${peer.deviceId}]")
                return Result.failure(IllegalStateException("Connection already in progress for ${peer.displayName}"))
            }

            if (currentState is PeerConnectionState.Connected && isConnected(peer.deviceId)) {
                Log.d(TAG, "Peer ${peer.displayName} is already connected")
                return Result.success(Unit)
            }

            // Cache peer for future reconnection
            lastKnownPeers[peer.deviceId] = peer

            val stateFlow = getOrCreateStateFlow(peer.deviceId)
            stateFlow.value = PeerConnectionState.Connecting

            // Create new BluetoothPeerConnection
            val peerConnection = BluetoothPeerConnection(
                peerId = peer.deviceId,
                context = context,
                bluetoothAdapter = bluetoothAdapter,
                scope = scope,
                ioDispatcher = ioDispatcher
            )

            // Replace any existing connection
            activeConnections.remove(peer.deviceId)?.release()
            activeConnections[peer.deviceId] = peerConnection

            // Monitor state changes
            val stateObservationJob = scope.launch {
                peerConnection.connectionState.collect { state ->
                    stateFlow.value = state
                    if (state is PeerConnectionState.Disconnected ||
                        state is PeerConnectionState.ConnectionLost ||
                        state is PeerConnectionState.ConnectionFailed ||
                        state is PeerConnectionState.BluetoothDisabled
                    ) {
                        activeConnections.remove(peer.deviceId)
                    }
                }
            }

            val connectJob = scope.launch {
                val result = peerConnection.connect(peer)
                if (result.isFailure) {
                    Log.w(TAG, "Connection to ${peer.displayName} failed: ${result.exceptionOrNull()?.message}")
                    activeConnections.remove(peer.deviceId)
                }
            }

            connectionJobs[peer.deviceId] = connectJob

            // Await completion or return result
            return try {
                connectJob.join()
                stateObservationJob.cancel() // Don't leak temporary job; main state is already set
                if (peerConnection.isConnected) {
                    Result.success(Unit)
                } else {
                    val finalState = stateFlow.value
                    val failureMessage = when (finalState) {
                        is PeerConnectionState.ConnectionFailed -> finalState.reason
                        is PeerConnectionState.ConnectionRejected -> finalState.reason
                        is PeerConnectionState.ConnectionTimeout -> "Connection timed out"
                        is PeerConnectionState.BluetoothDisabled -> "Bluetooth was turned off"
                        else -> "Failed to establish Bluetooth connection"
                    }
                    Result.failure(IOException(failureMessage))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while awaiting connection to ${peer.displayName}", e)
                activeConnections.remove(peer.deviceId)
                Result.failure(e)
            } finally {
                connectionJobs.remove(peer.deviceId)
            }
        }
    }

    override suspend fun cancelConnection(peerId: String) {
        connectionJobs.remove(peerId)?.let { job ->
            Log.d(TAG, "Cancelling connection job for peer: $peerId")
            job.cancel()
        }
        activeConnections.remove(peerId)?.let { connection ->
            connection.disconnect()
            connection.release()
        }
        getOrCreateStateFlow(peerId).value = PeerConnectionState.Disconnected
    }

    override suspend fun disconnect(peerId: String) {
        connectionJobs.remove(peerId)?.cancel()
        activeConnections.remove(peerId)?.let { connection ->
            connection.disconnect()
            connection.release()
        }
        getOrCreateStateFlow(peerId).value = PeerConnectionState.Disconnected
    }

    override suspend fun disconnectAll() {
        isListening.set(false)
        serverListenJob?.cancel()
        serverListenJob = null

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }
        serverSocket = null

        connectionJobs.values.forEach { it.cancel() }
        connectionJobs.clear()

        activeConnections.forEach { (peerId, connection) ->
            getOrCreateStateFlow(peerId).value = PeerConnectionState.Disconnected
            connection.release()
        }
        activeConnections.clear()
    }
}
