package com.offlinechat.transport.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WpsInfo
import android.os.Build
import android.util.Log
import com.offlinechat.core.model.DeviceIdentity
import com.offlinechat.core.model.TransportType
import com.offlinechat.core.result.AppError
import com.offlinechat.core.result.AppResult
import com.offlinechat.core.util.TimeProvider
import com.offlinechat.security.IdentityManager
import com.offlinechat.security.SessionCrypto
import com.offlinechat.security.model.DeviceCertificate
import com.offlinechat.transport.api.PeerDevice
import com.offlinechat.transport.api.Transport
import com.offlinechat.transport.api.TransportChannel
import com.offlinechat.transport.api.TransportEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Wi-Fi Direct (P2P) transport implementation using [WifiP2pManager]
 * and TCP socket communication with length-prefix framing over the established P2P group.
 */
@SuppressLint("MissingPermission")
@Singleton
class WiFiDirectTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager,
    private val sessionCrypto: SessionCrypto,
    private val timeProvider: TimeProvider,
) : Transport {

    companion object {
        private const val TAG = "WiFiDirectTransport"
        const val TCP_PORT = 8988
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val SOCKET_TIMEOUT_MS = 10_000
    }

    override val type: TransportType = TransportType.WIFI_DIRECT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val p2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null
    private val isReceiverRegistered = AtomicBoolean(false)

    private val _events = MutableSharedFlow<TransportEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    private var serverSocket: ServerSocket? = null
    private val isListening = AtomicBoolean(false)

    // Deferred object to capture connection info when Wi-Fi P2P connection changes
    private var pendingConnectionDeferred: CompletableDeferred<WifiP2pInfo>? = null

    // Cache discovered peers by MAC address to resolve during handshake
    private val discoveredPeersByAddress = ConcurrentHashMap<String, PeerDevice>()

    // ── Broadcast Receiver ───────────────────────────────────────────────────

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "WIFI_P2P_STATE_CHANGED_ACTION: isEnabled=$isEnabled")
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION: requesting peers")
                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    Log.d(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION: isConnected=${networkInfo?.isConnected}")
                    if (networkInfo?.isConnected == true) {
                        channel?.let { ch ->
                            p2pManager?.requestConnectionInfo(ch) { info ->
                                Log.d(TAG, "Connection info: groupFormed=${info?.groupFormed}, isGroupOwner=${info?.isGroupOwner}")
                                if (info != null && info.groupFormed) {
                                    pendingConnectionDeferred?.complete(info)
                                }
                            }
                        }
                    } else {
                        pendingConnectionDeferred?.completeExceptionally(
                            IllegalStateException("Wi-Fi Direct disconnected during negotiation")
                        )
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    Log.d(TAG, "This device changed: name=${device?.deviceName}, status=${device?.status}")
                }
            }
        }
    }

    init {
        ensureChannelInitialized()
    }

    private fun ensureChannelInitialized() {
        if (channel == null && p2pManager != null) {
            channel = p2pManager.initialize(context, context.mainLooper) {
                Log.w(TAG, "Wi-Fi Direct channel disconnected")
                channel = null
            }
        }
    }

    private fun registerReceiverIfNeeded() {
        if (isReceiverRegistered.compareAndSet(false, true)) {
            val intentFilter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(p2pReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(p2pReceiver, intentFilter)
            }
        }
    }

    private fun unregisterReceiverIfNeeded() {
        if (isReceiverRegistered.compareAndSet(true, false)) {
            runCatching { context.unregisterReceiver(p2pReceiver) }
        }
    }

    // ── Transport API ────────────────────────────────────────────────────────

    override fun events(): Flow<TransportEvent> = _events.asSharedFlow()

    override suspend fun startAdvertising(identity: DeviceIdentity): AppResult<Unit> {
        ensureChannelInitialized()
        registerReceiverIfNeeded()
        // Being discoverable in Wi-Fi Direct is activated via discoverPeers or group formation
        return startDiscovery()
    }

    override suspend fun stopAdvertising() {
        // Stop discovery and release group if any
        stopDiscovery()
    }

    override suspend fun startDiscovery(): AppResult<Unit> = withContext(Dispatchers.IO) {
        val manager = p2pManager
            ?: return@withContext AppResult.Failure(AppError.Unknown("Wi-Fi Direct not supported on this device"))

        ensureChannelInitialized()
        val ch = channel
            ?: return@withContext AppResult.Failure(AppError.Unknown("Failed to initialize Wi-Fi Direct channel"))

        registerReceiverIfNeeded()

        val deferred = CompletableDeferred<AppResult<Unit>>()
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct peer discovery started successfully")
                deferred.complete(AppResult.Success(Unit))
            }

            override fun onFailure(reason: Int) {
                val errorMsg = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported"
                    WifiP2pManager.BUSY -> "Wi-Fi Direct framework is busy"
                    WifiP2pManager.ERROR -> "Internal Wi-Fi Direct error"
                    else -> "Unknown error code $reason"
                }
                Log.e(TAG, "Wi-Fi Direct peer discovery failed: $errorMsg")
                deferred.complete(AppResult.Failure(AppError.Unknown("Wi-Fi Direct discovery failed: $errorMsg")))
            }
        })

        deferred.await()
    }

    override suspend fun stopDiscovery() {
        val manager = p2pManager ?: return
        val ch = channel ?: return
        manager.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct peer discovery stopped")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to stop Wi-Fi Direct peer discovery: reason=$reason")
            }
        })
    }

    private fun requestPeers() {
        val manager = p2pManager ?: return
        val ch = channel ?: return

        manager.requestPeers(ch) { peersList ->
            val now = timeProvider.nowMillis()
            peersList.deviceList.forEach { device ->
                val mac = device.deviceAddress ?: return@forEach
                val idFromMac = mac.replace(":", "").lowercase()
                val peer = PeerDevice(
                    deviceId = idFromMac,
                    displayName = device.deviceName.ifBlank { "Wi-Fi Peer ($idFromMac)" },
                    bluetoothAddress = mac, // P2P device MAC
                    publicSigningKeyBytes = ByteArray(0),
                    publicDhKeyBytes = ByteArray(0),
                    rssi = -50,
                    transport = TransportType.WIFI_DIRECT,
                    discoveredAt = now,
                )
                discoveredPeersByAddress[mac] = peer
                discoveredPeersByAddress[idFromMac] = peer
                scope.launch {
                    _events.emit(TransportEvent.PeerDiscovered(peer))
                }
            }
        }
    }

    override suspend fun connect(peer: PeerDevice): AppResult<TransportChannel> = withContext(Dispatchers.IO) {
        val manager = p2pManager
            ?: return@withContext AppResult.Failure(AppError.PeerNotReachable, Exception("Wi-Fi Direct not supported"))

        ensureChannelInitialized()
        val ch = channel
            ?: return@withContext AppResult.Failure(AppError.PeerNotReachable, Exception("P2P Channel not initialized"))

        val targetAddress = peer.bluetoothAddress ?: run {
            discoveredPeersByAddress[peer.deviceId]?.bluetoothAddress
        } ?: return@withContext AppResult.Failure(
            AppError.PeerNotReachable,
            IllegalArgumentException("Missing Wi-Fi Direct MAC address for peer ${peer.deviceId}")
        )

        registerReceiverIfNeeded()

        val config = WifiP2pConfig().apply {
            deviceAddress = targetAddress
            wps.setup = WpsInfo.PBC
        }

        val connectionDeferred = CompletableDeferred<WifiP2pInfo>()
        pendingConnectionDeferred = connectionDeferred

        val connectInitiatedDeferred = CompletableDeferred<Boolean>()
        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "WifiP2pManager.connect initiated successfully for $targetAddress")
                connectInitiatedDeferred.complete(true)
            }

            override fun onFailure(reason: Int) {
                val errorMsg = "P2P connect failed with code $reason"
                Log.e(TAG, errorMsg)
                connectInitiatedDeferred.complete(false)
            }
        })

        val initiated = connectInitiatedDeferred.await()
        if (!initiated) {
            pendingConnectionDeferred = null
            _events.emit(TransportEvent.ConnectionFailed(peer.deviceId, "Wi-Fi Direct connect initiation failed"))
            return@withContext AppResult.Failure(AppError.PeerNotReachable)
        }

        // Await group formation and connection info with bounded timeout
        val connectionInfo: WifiP2pInfo = try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                connectionDeferred.await()
            }
        } catch (e: Exception) {
            pendingConnectionDeferred = null
            manager.cancelConnect(ch, null)
            _events.emit(TransportEvent.ConnectionFailed(peer.deviceId, "P2P group formation timed out or failed: ${e.message}"))
            return@withContext AppResult.Failure(AppError.PeerNotReachable, e)
        } finally {
            pendingConnectionDeferred = null
        }

        // Establish TCP socket channel
        var socket: Socket? = null
        try {
            socket = withTimeout(CONNECT_TIMEOUT_MS) {
                if (connectionInfo.isGroupOwner) {
                    // This device is Group Owner — accept incoming connection from peer
                    ensureServerRunning()
                    val s = serverSocket?.accept() ?: error("Server socket not listening")
                    s.soTimeout = SOCKET_TIMEOUT_MS
                    s
                } else {
                    // This device is Client — connect to Group Owner's IP
                    val goAddress = connectionInfo.groupOwnerAddress?.hostAddress
                        ?: error("Group Owner IP address missing in WifiP2pInfo")
                    val s = Socket()
                    s.connect(InetSocketAddress(goAddress, TCP_PORT), SOCKET_TIMEOUT_MS)
                    s.soTimeout = SOCKET_TIMEOUT_MS
                    s
                }
            }

            val channelResult = performHandshake(socket, peer)
            if (channelResult is AppResult.Failure) {
                throw (channelResult.cause ?: Exception("Handshake failed: ${channelResult.error}"))
            }

            (channelResult as AppResult.Success).data.let { AppResult.Success(it) }
        } catch (e: Throwable) {
            runCatching { socket?.close() }
            _events.emit(TransportEvent.ConnectionFailed(peer.deviceId, "Wi-Fi Direct socket or handshake failed: ${e.message}"))
            AppResult.Failure(AppError.PeerNotReachable, e)
        }
    }

    override fun listen(): Flow<AppResult<TransportChannel>> = flow {
        ensureChannelInitialized()
        registerReceiverIfNeeded()

        ensureServerRunning()
        val server = serverSocket ?: run {
            emit(AppResult.Failure(AppError.Unknown("Failed to start TCP ServerSocket for Wi-Fi Direct")))
            return@flow
        }

        try {
            while (isListening.get()) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val channelResult = performHandshake(socket, peerFromSocket = null)
                if (channelResult is AppResult.Failure) {
                    _events.emit(
                        TransportEvent.ConnectionFailed(
                            peerId = "inbound_wifi",
                            reason = "Inbound Wi-Fi Direct handshake failed: ${channelResult.error} (${channelResult.cause?.message ?: "unknown"})",
                        )
                    )
                }
                emit(channelResult)
            }
        } finally {
            runCatching { server.close() }
            serverSocket = null
            isListening.set(false)
        }
    }.flowOn(Dispatchers.IO)

    @Synchronized
    private fun ensureServerRunning() {
        if (serverSocket == null || serverSocket?.isClosed == true) {
            serverSocket = ServerSocket(TCP_PORT)
            isListening.set(true)
            Log.i(TAG, "Wi-Fi Direct TCP ServerSocket started on port $TCP_PORT")
        }
    }

    override suspend fun shutdown() {
        isListening.set(false)
        stopDiscovery()
        unregisterReceiverIfNeeded()

        val manager = p2pManager
        val ch = channel
        if (manager != null && ch != null) {
            manager.removeGroup(ch, null)
        }

        withContext(Dispatchers.IO) {
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    // ── Handshake ─────────────────────────────────────────────────────────────

    private suspend fun performHandshake(
        socket: Socket,
        peerFromSocket: PeerDevice?,
    ): AppResult<TransportChannel> = withContext(Dispatchers.IO) {
        runCatching {
            // Step 1: Generate ephemeral DH keypair
            val ephemeralKeyPair = when (val res = sessionCrypto.generateEphemeralDhKeyPair()) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> error("Failed to generate ephemeral DH keypair: ${res.error}")
            }

            // Step 2: Issue our certificate
            val ourCert = when (val res = identityManager.issueCertificate(ephemeralKeyPair.public)) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> error("Failed to issue certificate: ${res.error}")
            }

            // Step 3: Send our certificate with 4-byte length prefix
            val certJson = Json.encodeToString(DeviceCertificate.serializer(), ourCert)
            val certBytes = certJson.toByteArray(Charsets.UTF_8)
            val dataOut = DataOutputStream(socket.getOutputStream())
            dataOut.writeInt(certBytes.size)
            dataOut.write(certBytes)
            dataOut.flush()

            // Step 4: Receive peer's certificate
            val dataIn = DataInputStream(socket.getInputStream())
            val peerCertLength = dataIn.readInt()
            require(peerCertLength in 1..65_536) { "Invalid cert length: $peerCertLength" }
            val peerCertBytes = ByteArray(peerCertLength)
            dataIn.readFully(peerCertBytes)
            val peerCert = Json.decodeFromString(
                DeviceCertificate.serializer(),
                String(peerCertBytes, Charsets.UTF_8),
            )

            // Step 5: Verify peer's certificate
            when (val res = identityManager.verifyCertificate(peerCert)) {
                is AppResult.Success -> { /* Verified */ }
                is AppResult.Failure -> error("Peer certificate verification failed: ${res.error}")
            }

            // Decode peer's ephemeral DH public key
            val peerDhKeyBytes = try {
                Base64.getDecoder().decode(peerCert.publicDhKeyBase64)
            } catch (e: Exception) {
                error("Failed to decode peer DH public key: ${e.message}")
            }

            // Step 6: ECDH shared secret
            val sharedSecret = when (val res = sessionCrypto.computeSharedSecret(
                ourEphemeralPrivateKey = ephemeralKeyPair.private,
                peerEphemeralPublicKeyBytes = peerDhKeyBytes,
            )) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> error("ECDH computeSharedSecret failed: ${res.error}")
            }

            // Step 7: Derive session key via HKDF
            val localIdentity = when (val res = identityManager.getLocalIdentity()) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> error("Failed to get local identity: ${res.error}")
            }
            val localId = localIdentity.id
            val peerId = peerCert.deviceId
            val (initiatorId, responderId) = if (localId < peerId) {
                localId to peerId
            } else {
                peerId to localId
            }

            val sessionKey = when (val res = sessionCrypto.deriveSessionKey(sharedSecret, initiatorId, responderId)) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> error("deriveSessionKey failed: ${res.error}")
            }

            // Build verified PeerDevice
            val signingKeyBytes = try {
                Base64.getDecoder().decode(peerCert.publicSigningKeyBase64)
            } catch (e: Exception) {
                error("Failed to decode peer signing key: ${e.message}")
            }

            val finalPeer = PeerDevice(
                deviceId = peerCert.deviceId,
                displayName = peerCert.displayName,
                bluetoothAddress = peerFromSocket?.bluetoothAddress ?: socket.inetAddress?.hostAddress,
                publicSigningKeyBytes = signingKeyBytes,
                publicDhKeyBytes = peerDhKeyBytes,
                rssi = peerFromSocket?.rssi ?: -50,
                transport = TransportType.WIFI_DIRECT,
                discoveredAt = timeProvider.nowMillis(),
            )

            val channel = WiFiDirectChannel(
                socket = socket,
                peerId = peerId,
                sessionKey = sessionKey,
            )

            _events.emit(TransportEvent.ChannelOpened(peer = finalPeer, channel = channel))
            channel as TransportChannel
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { e ->
                runCatching { socket.close() }
                AppResult.Failure(AppError.Unknown("Handshake failed: ${e.message}"), e)
            },
        )
    }
}
