package com.offlinechat.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Transport] implementation that uses:
 * - **BLE advertising/scanning** for device discovery (via [BleAdvertiser] / [BleScanner])
 * - **Bluetooth Classic RFCOMM** for data transfer (via [RfcommChannel])
 */
@SuppressLint("MissingPermission")
@Singleton
class BluetoothTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleAdvertiser: BleAdvertiser,
    private val bleScanner: BleScanner,
    private val identityManager: IdentityManager,
    private val sessionCrypto: SessionCrypto,
    private val timeProvider: TimeProvider,
) : Transport {

    companion object {
        /**
         * RFCOMM Service Record UUID for OfflineChat.
         * Peers use this UUID when opening a socket via [createRfcommSocketToServiceRecord].
         */
        val RFCOMM_UUID: UUID = UUID.fromString("B2C3D4E5-F6A7-8901-BCDE-F12345678901")

        private const val SERVER_NAME = "OfflineChat"
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    override val type: TransportType = TransportType.BLUETOOTH

    // Shared event bus — all internal events are pushed here and exposed via events()
    private val _events = MutableSharedFlow<TransportEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    private var serverSocket: BluetoothServerSocket? = null

    // ── Transport interface ───────────────────────────────────────────────────

    override fun events(): Flow<TransportEvent> = _events.asSharedFlow()

    override suspend fun startAdvertising(identity: DeviceIdentity): AppResult<Unit> =
        bleAdvertiser.start(identity)

    override suspend fun stopAdvertising() {
        bleAdvertiser.stop()
    }

    /**
     * Starts BLE scanning and bridges scan events to [TransportEvent]s.
     */
    override suspend fun startDiscovery(): AppResult<Unit> {
        val scanResult = bleScanner.scan(lowPower = false)
        if (scanResult is AppResult.Failure) return scanResult

        val scanFlow = (scanResult as AppResult.Success).data
        scanFlow
            .catch { e -> _events.emit(TransportEvent.TransportError(e, "BLE scan error")) }
            .collect { event ->
                when (event) {
                    is ScanEvent.Found -> {
                        _events.emit(TransportEvent.PeerDiscovered(event.peer))
                    }
                    is ScanEvent.Lost -> {
                        _events.emit(TransportEvent.PeerLost(event.bluetoothAddress))
                    }
                }
            }

        return AppResult.Success(Unit)
    }

    override suspend fun stopDiscovery() {
        // Scanning is stopped by cancelling the coroutine collecting the scan Flow.
    }

    /**
     * Opens an RFCOMM socket to [peer] and performs the identity handshake.
     */
    override suspend fun connect(peer: PeerDevice): AppResult<TransportChannel> {
        val btAddress = peer.bluetoothAddress
            ?: return AppResult.Failure(AppError.PeerNotReachable)

        val adapter = getBluetoothAdapter()
            ?: return AppResult.Failure(AppError.BluetoothNotAvailable)

        if (!adapter.isEnabled) {
            return AppResult.Failure(AppError.BluetoothNotAvailable)
        }

        return withContext(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    val remoteDevice: BluetoothDevice = adapter.getRemoteDevice(btAddress)
                    val s: BluetoothSocket = remoteDevice.createRfcommSocketToServiceRecord(RFCOMM_UUID)
                    socket = s

                    // Stop discovery during connection to improve success rate
                    adapter.cancelDiscovery()

                    s.connect()

                    val handshakeResult = performHandshake(s, peer)
                    if (handshakeResult is AppResult.Failure) {
                        throw (handshakeResult.cause ?: Exception("Handshake failed: ${handshakeResult.error}"))
                    }

                    (handshakeResult as AppResult.Success).data
                }.let { channel ->
                    AppResult.Success(channel)
                }
            } catch (e: Throwable) {
                runCatching { socket?.close() }
                val reason = e.message ?: "RFCOMM connect failed"
                _events.emit(
                    TransportEvent.ConnectionFailed(
                        peerId = peer.deviceId,
                        reason = reason,
                    ),
                )
                AppResult.Failure(AppError.PeerNotReachable, e)
            }
        }
    }

    /**
     * Listens for incoming RFCOMM connections.
     */
    override fun listen(): Flow<AppResult<TransportChannel>> = flow {
        val adapter = getBluetoothAdapter() ?: run {
            emit(AppResult.Failure(AppError.BluetoothNotAvailable))
            return@flow
        }

        if (!adapter.isEnabled) {
            emit(AppResult.Failure(AppError.BluetoothNotAvailable))
            return@flow
        }

        val server = try {
            adapter.listenUsingRfcommWithServiceRecord(SERVER_NAME, RFCOMM_UUID)
        } catch (e: Exception) {
            _events.emit(TransportEvent.TransportError(e, "Failed to start RFCOMM server listener"))
            emit(AppResult.Failure(AppError.BluetoothPermissionDenied, e))
            return@flow
        }
        serverSocket = server

        try {
            while (true) {
                val socket = try {
                    server.accept()  // Blocking — waits for a client
                } catch (e: Exception) {
                    break
                }
                val channelResult = performHandshake(socket, peerFromSocket = null)
                if (channelResult is AppResult.Failure) {
                    _events.emit(
                        TransportEvent.ConnectionFailed(
                            peerId = "inbound",
                            reason = "Inbound handshake failed: ${channelResult.error}",
                        ),
                    )
                }
                emit(channelResult)
            }
        } finally {
            runCatching { server.close() }
            serverSocket = null
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun shutdown() {
        bleAdvertiser.stop()
        withContext(Dispatchers.IO) {
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    // ── Handshake ─────────────────────────────────────────────────────────────

    /**
     * Performs the RFCOMM identity + key exchange handshake.
     */
    private suspend fun performHandshake(
        socket: BluetoothSocket,
        peerFromSocket: PeerDevice?,
    ): AppResult<TransportChannel> = withContext(Dispatchers.IO) {
        runCatching {
            // Step 1: Generate ephemeral DH keypair for this session
            val ephemeralKeyPair = when (val res = sessionCrypto.generateEphemeralDhKeyPair()) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> error("Failed to generate ephemeral DH keypair: ${res.error}")
            }

            // Step 2: Issue our certificate
            val ourCert = when (val res = identityManager.issueCertificate(ephemeralKeyPair.public)) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> error("Failed to issue certificate: ${res.error}")
            }

            // Step 3: Send our certificate
            val certJson = Json.encodeToString(DeviceCertificate.serializer(), ourCert)
            val certBytes = certJson.toByteArray(Charsets.UTF_8)
            val dataOut = DataOutputStream(socket.outputStream)
            dataOut.writeInt(certBytes.size)
            dataOut.write(certBytes)
            dataOut.flush()

            // Step 4: Receive peer's certificate
            val dataIn = DataInputStream(socket.inputStream)
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
                is AppResult.Success -> { /* valid */ }
                is AppResult.Failure -> error("Peer certificate verification failed: ${res.error}")
            }

            // Decode the peer's ephemeral DH public key
            val peerDhKeyBytes = try {
                Base64.getDecoder().decode(peerCert.publicDhKeyBase64)
            } catch (e: Exception) {
                error("Failed to decode peer DH key: ${e.message}")
            }

            // Step 6: ECDH
            val sharedSecret = when (val res = sessionCrypto.computeSharedSecret(
                ourEphemeralPrivateKey = ephemeralKeyPair.private,
                peerEphemeralPublicKeyBytes = peerDhKeyBytes,
            )) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> error("ECDH computeSharedSecret failed: ${res.error}")
            }

            // Step 7: Derive session key with HKDF
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

            // Build the final PeerDevice with full key material from the certificate
            val signingKeyBytes = try {
                Base64.getDecoder().decode(peerCert.publicSigningKeyBase64)
            } catch (e: Exception) {
                error("Failed to decode peer signing key: ${e.message}")
            }

            val finalPeer = PeerDevice(
                deviceId = peerCert.deviceId,
                displayName = peerCert.displayName,
                bluetoothAddress = socket.remoteDevice?.address ?: peerFromSocket?.bluetoothAddress,
                publicSigningKeyBytes = signingKeyBytes,
                publicDhKeyBytes = peerDhKeyBytes,
                rssi = peerFromSocket?.rssi ?: 0,
                transport = TransportType.BLUETOOTH,
                discoveredAt = timeProvider.nowMillis(),
            )

            val channel = RfcommChannel(
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getBluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}
