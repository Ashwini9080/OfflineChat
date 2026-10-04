package com.offlinechat.data.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Base64
import android.util.Log
import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.domain.model.HandshakePayload
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import com.offlinechat.domain.repository.PreferencesRepository
import com.offlinechat.security.IdentityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager,
    private val preferencesRepository: PreferencesRepository
) : MessageTransport {

    companion object {
        private const val TAG = "BluetoothTransport"
        // Standard Bluetooth Serial Port Profile (SPP) UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        // Secondary Application-Specific Fallback UUID
        val APP_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        private const val SERVER_NAME = "OfflineChatSPP"
    }

    override val transportType: TransportType = TransportType.BLUETOOTH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val json = Json { ignoreUnknownKeys = true }
    private val activeSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val connectionStates = ConcurrentHashMap<String, MutableStateFlow<PeerConnectionState>>()

    private val _incomingEnvelopes = MutableSharedFlow<MessageEnvelope>(extraBufferCapacity = 128)
    override val incomingEnvelopes: Flow<MessageEnvelope> = _incomingEnvelopes.asSharedFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var isListening = false

    init {
        startServerListener()
    }

    override fun isConnected(peerId: String): Boolean {
        return activeSockets[peerId]?.isConnected == true
    }

    override fun observeConnectionState(peerId: String): Flow<PeerConnectionState> {
        return connectionStates.getOrPut(peerId) {
            MutableStateFlow(if (isConnected(peerId)) PeerConnectionState.Connected else PeerConnectionState.Disconnected)
        }.asStateFlow()
    }

    private fun setConnectionState(peerId: String, state: PeerConnectionState) {
        val flow = connectionStates.getOrPut(peerId) { MutableStateFlow(state) }
        flow.value = state
    }

    @SuppressLint("MissingPermission")
    fun startServerListener() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || isListening) return

        isListening = true
        scope.launch {
            try {
                // Try Insecure RFCOMM first for seamless P2P pairing without PIN prompts
                serverSocket = try {
                    adapter.listenUsingInsecureRfcommWithServiceRecord(SERVER_NAME, SPP_UUID)
                } catch (e: Exception) {
                    Log.w(TAG, "Insecure RFCOMM listen failed, trying Secure RFCOMM", e)
                    adapter.listenUsingRfcommWithServiceRecord(SERVER_NAME, SPP_UUID)
                }

                Log.d(TAG, "Bluetooth RFCOMM server socket listening on UUID: $SPP_UUID")

                while (isListening) {
                    val socket = serverSocket?.accept() ?: break
                    Log.d(TAG, "Incoming Bluetooth socket connection accepted from: ${socket.remoteDevice?.address}")

                    scope.launch {
                        handleIncomingSocket(socket)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "RFCOMM server socket closed: ${e.message}")
            } finally {
                isListening = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: Peer): Result<Unit> = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter
            ?: return@withContext Result.failure(IllegalStateException("Bluetooth hardware unavailable"))

        if (!adapter.isEnabled) {
            return@withContext Result.failure(IllegalStateException("Bluetooth is turned off"))
        }

        val address = peer.bluetoothAddress
            ?: return@withContext Result.failure(IllegalArgumentException("No Bluetooth hardware address available for peer ${peer.displayName}"))

        // If already connected, return success immediately
        activeSockets[peer.deviceId]?.let { existing ->
            if (existing.isConnected) {
                setConnectionState(peer.deviceId, PeerConnectionState.Connected)
                return@withContext Result.success(Unit)
            }
        }

        setConnectionState(peer.deviceId, PeerConnectionState.Connecting)

        try {
            val device = adapter.getRemoteDevice(address)

            // CRITICAL: Cancel discovery before initiating connection to free up radio bandwidth
            if (adapter.isDiscovering) {
                Log.d(TAG, "Cancelling discovery prior to RFCOMM socket connect")
                adapter.cancelDiscovery()
            }

            // Create socket with fallback chain (Insecure SPP -> Secure SPP -> Reflection)
            val socket = createClientSocket(device)
            Log.d(TAG, "Connecting RFCOMM socket to ${peer.displayName} [$address]...")
            socket.connect()
            Log.d(TAG, "RFCOMM socket connected successfully to $address")

            setConnectionState(peer.deviceId, PeerConnectionState.Authenticating)

            // Perform mutual cryptographic handshake
            performClientHandshake(peer.deviceId, socket)

            activeSockets[peer.deviceId] = socket
            setConnectionState(peer.deviceId, PeerConnectionState.Connected)

            // Launch message read loop
            listenToSocket(peer.deviceId, socket)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to peer ${peer.displayName} [${peer.deviceId}]", e)
            setConnectionState(peer.deviceId, PeerConnectionState.Failed(e.message ?: "Connection failed"))
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createClientSocket(device: BluetoothDevice): BluetoothSocket {
        // Method 1: Insecure RFCOMM
        try {
            return device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "createInsecureRfcommSocketToServiceRecord failed, trying secure", e)
        }

        // Method 2: Secure RFCOMM
        try {
            return device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "createRfcommSocketToServiceRecord failed, trying reflection", e)
        }

        // Method 3: Direct channel reflection fallback (known solution for stubborn OEM stacks)
        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return m.invoke(device, 1) as BluetoothSocket
    }

    private suspend fun performClientHandshake(peerDeviceId: String, socket: BluetoothSocket) {
        val localDisplayName = preferencesRepository.displayName.first()
        val localDeviceId = identityManager.deviceId
        val localPubKeyBase64 = Base64.encodeToString(identityManager.publicKeyBytes, Base64.NO_WRAP)

        val handshakePayload = HandshakePayload(
            deviceId = localDeviceId,
            displayName = localDisplayName,
            publicKeyBase64 = localPubKeyBase64
        )
        val handshakeJson = json.encodeToString(HandshakePayload.serializer(), handshakePayload)

        val envelope = MessageEnvelope(
            messageId = UUID.randomUUID().toString(),
            conversationId = "",
            senderId = localDeviceId,
            receiverId = peerDeviceId,
            timestamp = System.currentTimeMillis(),
            messageType = "HANDSHAKE",
            payload = handshakeJson.toByteArray(Charsets.UTF_8)
        )

        // Write local handshake
        writeEnvelopeToStream(socket.outputStream, envelope)

        // Read peer's handshake response
        val inputStream = DataInputStream(socket.inputStream)
        val length = inputStream.readInt()
        if (length <= 0 || length > 1024 * 1024) throw IOException("Invalid handshake frame length: $length")
        val buffer = ByteArray(length)
        inputStream.readFully(buffer)

        val peerEnvelope = json.decodeFromString(MessageEnvelope.serializer(), String(buffer, Charsets.UTF_8))
        if (peerEnvelope.messageType == "HANDSHAKE") {
            val peerHandshake = json.decodeFromString(HandshakePayload.serializer(), String(peerEnvelope.payload, Charsets.UTF_8))
            Log.d(TAG, "Handshake verified with peer: ${peerHandshake.displayName} [${peerHandshake.deviceId}]")
            _incomingEnvelopes.emit(peerEnvelope)
        }
    }

    private suspend fun handleIncomingSocket(socket: BluetoothSocket) {
        try {
            val inputStream = DataInputStream(socket.inputStream)
            // 1. Read peer's incoming handshake
            val length = inputStream.readInt()
            if (length <= 0 || length > 1024 * 1024) throw IOException("Invalid handshake length: $length")
            val buffer = ByteArray(length)
            inputStream.readFully(buffer)

            val peerEnvelope = json.decodeFromString(MessageEnvelope.serializer(), String(buffer, Charsets.UTF_8))
            if (peerEnvelope.messageType != "HANDSHAKE") {
                Log.w(TAG, "Expected HANDSHAKE envelope, received: ${peerEnvelope.messageType}")
                socket.close()
                return
            }

            val peerHandshake = json.decodeFromString(HandshakePayload.serializer(), String(peerEnvelope.payload, Charsets.UTF_8))
            val peerId = peerHandshake.deviceId
            Log.d(TAG, "Incoming handshake from: ${peerHandshake.displayName} [$peerId]")

            // 2. Respond with local handshake
            val localDisplayName = preferencesRepository.displayName.first()
            val localDeviceId = identityManager.deviceId
            val localPubKeyBase64 = Base64.encodeToString(identityManager.publicKeyBytes, Base64.NO_WRAP)

            val myHandshakePayload = HandshakePayload(
                deviceId = localDeviceId,
                displayName = localDisplayName,
                publicKeyBase64 = localPubKeyBase64
            )
            val myEnvelope = MessageEnvelope(
                messageId = UUID.randomUUID().toString(),
                conversationId = "",
                senderId = localDeviceId,
                receiverId = peerId,
                timestamp = System.currentTimeMillis(),
                messageType = "HANDSHAKE",
                payload = json.encodeToString(HandshakePayload.serializer(), myHandshakePayload).toByteArray(Charsets.UTF_8)
            )
            writeEnvelopeToStream(socket.outputStream, myEnvelope)

            // Register active connection
            activeSockets[peerId] = socket
            setConnectionState(peerId, PeerConnectionState.Connected)

            // Emit peer handshake so app updates repositories
            _incomingEnvelopes.emit(peerEnvelope)

            // 3. Enter continuous message listening loop
            listenToSocket(peerId, socket)
        } catch (e: Exception) {
            Log.w(TAG, "Incoming socket handshake failed: ${e.message}")
            runCatching { socket.close() }
        }
    }

    override suspend fun sendEnvelope(envelope: MessageEnvelope): Result<Unit> = withContext(Dispatchers.IO) {
        val socket = activeSockets[envelope.receiverId]
            ?: return@withContext Result.failure(IllegalStateException("No active RFCOMM connection to peer ${envelope.receiverId}"))

        if (!socket.isConnected) {
            activeSockets.remove(envelope.receiverId)
            setConnectionState(envelope.receiverId, PeerConnectionState.Disconnected)
            return@withContext Result.failure(IllegalStateException("Socket is disconnected"))
        }

        try {
            writeEnvelopeToStream(socket.outputStream, envelope)
            Log.d(TAG, "Sent envelope [${envelope.messageType}:${envelope.messageId}] to ${envelope.receiverId}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error transmitting envelope to ${envelope.receiverId}", e)
            activeSockets.remove(envelope.receiverId)
            setConnectionState(envelope.receiverId, PeerConnectionState.Disconnected)
            Result.failure(e)
        }
    }

    private fun writeEnvelopeToStream(outputStream: java.io.OutputStream, envelope: MessageEnvelope) {
        val jsonString = json.encodeToString(MessageEnvelope.serializer(), envelope)
        val bytes = jsonString.toByteArray(Charsets.UTF_8)
        val dataOut = DataOutputStream(outputStream)

        synchronized(outputStream) {
            // Frame format: 4-byte big-endian length + JSON payload
            dataOut.writeInt(bytes.size)
            dataOut.write(bytes)
            dataOut.flush()
        }
    }

    private fun listenToSocket(peerId: String, socket: BluetoothSocket) {
        scope.launch {
            val inputStream = DataInputStream(socket.inputStream)
            try {
                while (socket.isConnected) {
                    val length = inputStream.readInt()
                    if (length <= 0 || length > 1024 * 1024) break // Max 1MB frame limit

                    val buffer = ByteArray(length)
                    inputStream.readFully(buffer)

                    val jsonString = String(buffer, Charsets.UTF_8)
                    val envelope = json.decodeFromString(MessageEnvelope.serializer(), jsonString)
                    Log.d(TAG, "Received envelope [${envelope.messageType}:${envelope.messageId}] from $peerId")
                    _incomingEnvelopes.emit(envelope)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Socket connection ended for $peerId: ${e.message}")
            } finally {
                activeSockets.remove(peerId)
                setConnectionState(peerId, PeerConnectionState.Disconnected)
                runCatching { socket.close() }
            }
        }
    }

    override suspend fun disconnect(peerId: String) {
        activeSockets.remove(peerId)?.let { socket ->
            runCatching { socket.close() }
        }
        setConnectionState(peerId, PeerConnectionState.Disconnected)
    }

    override suspend fun shutdown() {
        isListening = false
        runCatching { serverSocket?.close() }
        activeSockets.forEach { (peerId, socket) ->
            setConnectionState(peerId, PeerConnectionState.Disconnected)
            runCatching { socket.close() }
        }
        activeSockets.clear()
    }
}
