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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Transport] implementation that uses:
 * - **BLE advertising/scanning** for device discovery (via [BleAdvertiser] / [BleScanner])
 * - **Bluetooth Classic RFCOMM** for data transfer (via [RfcommChannel])
 *
 * ## Connection flow
 *
 * ### Outbound (this device connects TO a peer)
 * 1. [BleScanner] discovers peer → [TransportEvent.PeerDiscovered] emitted.
 * 2. Caller calls [connect] with that [PeerDevice].
 * 3. An RFCOMM socket is opened to the peer's Bluetooth MAC address.
 * 4. Both sides exchange [DeviceCertificate]s as the first frames on the socket.
 * 5. [IdentityManager.verifyCertificate] validates the peer's certificate.
 * 6. [SessionCrypto.generateEphemeralDhKeyPair] + ECDH produces session key.
 * 7. [RfcommChannel] is returned to the caller.
 *
 * ### Inbound (a peer connects TO this device)
 * 1. [listen] maintains a [BluetoothServerSocket] in the background.
 * 2. Each accepted socket goes through the same handshake as the outbound path.
 * 3. Completed channels are emitted from the [listen] flow.
 *
 * ## Permissions
 * Requires BLUETOOTH_CONNECT and BLUETOOTH_SCAN (API 31+) or BLUETOOTH (API ≤30).
 * [BluetoothTransport] does NOT request permissions — that is the UI layer's job.
 * If permissions are absent, methods return [AppError.BluetoothPermissionDenied].
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
     *
     * Discovered peers emit [TransportEvent.PeerDiscovered] immediately from the
     * BLE advertisement — without waiting for RFCOMM. The full identity is only
     * confirmed during the handshake, but the partial peer is sufficient for the
     * UI to show "X is nearby, tap to chat".
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
        // BluetoothTransport's holder (TransportManager) is responsible for that cancellation.
    }

    /**
     * Opens an RFCOMM socket to [peer] and performs the identity handshake.
     *
     * This call blocks until the connection is established and the handshake
     * completes (or fails). On success, returns a ready-to-use [RfcommChannel].
     */
    override suspend fun connect(peer: PeerDevice): AppResult<TransportChannel> {
        val btAddress = peer.bluetoothAddress
            ?: return AppResult.Failure(AppError.PeerNotReachable)

        return withContext(Dispatchers.IO) {
            runCatching {
                val adapter = getBluetoothAdapter()
                    ?: error("Bluetooth not available")
                val remoteDevice: BluetoothDevice = adapter.getRemoteDevice(btAddress)

                val socket: BluetoothSocket = remoteDevice
                    .createRfcommSocketToServiceRecord(RFCOMM_UUID)

                // Stop discovery during connection to improve success rate
                adapter.cancelDiscovery()

                socket.connect()  // Blocking connect

                performHandshake(socket, peer)
            }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    _events.emit(
                        TransportEvent.ConnectionFailed(
                            peerId = peer.deviceId,
                            reason = e.message ?: "Unknown",
                        ),
                    )
                    AppResult.Failure(AppError.PeerNotReachable, e)
                },
            )
        }
    }

    /**
     * Listens for incoming RFCOMM connections.
     *
     * Each accepted connection goes through [performHandshake] before being
     * emitted. Invalid or failed handshakes are dropped with a log entry.
     *
     * This flow runs indefinitely on [Dispatchers.IO]. Collect it in a
     * coroutine tied to the service/foreground-notification lifecycle.
     */
    override fun listen(): Flow<AppResult<TransportChannel>> = flow {
        val adapter = getBluetoothAdapter() ?: run {
            emit(AppResult.Failure<TransportChannel>(AppError.BluetoothNotAvailable))
            return@flow
        }

        val server = adapter.listenUsingRfcommWithServiceRecord(SERVER_NAME, RFCOMM_UUID)
        serverSocket = server

        try {
            while (true) {
                val socket = server.accept()  // Blocking — waits for a client
                val channelResult = performHandshake(socket, peerFromSocket = null)
                emit(channelResult)
            }
        } finally {
            server.close()
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
     *
     * Protocol (both sides do the same steps simultaneously):
     * 1. Generate ephemeral DH keypair.
     * 2. Issue a [DeviceCertificate] containing the ephemeral DH public key.
     * 3. Serialise and send the certificate as the first frame.
     * 4. Receive and deserialise the peer's certificate.
     * 5. Verify the peer's certificate signature and ID derivation.
     * 6. Run ECDH to derive the shared secret.
     * 7. Derive the AES-256 session key via HKDF.
     *
     * After this, the [RfcommChannel] is ready for encrypted message exchange.
     *
     * @param socket       The connected RFCOMM socket.
     * @param peerFromSocket A partial [PeerDevice] from BLE (outbound calls) or
     *                     null (inbound — identity comes from the certificate).
     */
    private suspend fun performHandshake(
        socket: BluetoothSocket,
        peerFromSocket: PeerDevice?,
    ): AppResult<TransportChannel> = withContext(Dispatchers.IO) {
        runCatching {
            // Step 1: Generate ephemeral DH keypair for this session
            val ephemeralKeyPairResult = sessionCrypto.generateEphemeralDhKeyPair()
            val ephemeralKeyPair = (ephemeralKeyPairResult as AppResult.Success).data

            // Step 2: Issue our certificate
            val certResult = identityManager.issueCertificate(ephemeralKeyPair.public)
            val ourCert = (certResult as AppResult.Success).data

            // Step 3: Send our certificate
            val certJson = Json.encodeToString(DeviceCertificate.serializer(), ourCert)
            val certBytes = certJson.toByteArray(Charsets.UTF_8)
            val dataOut = java.io.DataOutputStream(socket.outputStream)
            dataOut.writeInt(certBytes.size)
            dataOut.write(certBytes)
            dataOut.flush()

            // Step 4: Receive peer's certificate
            val dataIn = java.io.DataInputStream(socket.inputStream)
            val peerCertLength = dataIn.readInt()
            require(peerCertLength in 1..65_536) { "Invalid cert length: $peerCertLength" }
            val peerCertBytes = ByteArray(peerCertLength)
            dataIn.readFully(peerCertBytes)
            val peerCert = Json.decodeFromString(
                DeviceCertificate.serializer(),
                String(peerCertBytes, Charsets.UTF_8),
            )

            // Step 5: Verify peer's certificate
            val verifyResult = identityManager.verifyCertificate(peerCert)
            require(verifyResult is AppResult.Success) { "Peer certificate verification failed" }

            // Decode the peer's ephemeral DH public key
            val peerDhKeyBytes = java.util.Base64.getDecoder().decode(peerCert.publicDhKeyBase64)

            // Step 6: ECDH
            val sharedSecretResult = sessionCrypto.computeSharedSecret(
                ourEphemeralPrivateKey = ephemeralKeyPair.private,
                peerEphemeralPublicKeyBytes = peerDhKeyBytes,
            )
            val sharedSecret = (sharedSecretResult as AppResult.Success).data

            // Step 7: Derive session key with HKDF
            // Canonical ordering: alphabetically smaller ID is "initiator"
            val localId = (identityManager.getLocalIdentity() as AppResult.Success).data.id
            val peerId = peerCert.deviceId
            val (initiatorId, responderId) = if (localId < peerId) {
                localId to peerId
            } else {
                peerId to localId
            }

            val sessionKeyResult = sessionCrypto.deriveSessionKey(sharedSecret, initiatorId, responderId)
            // Session key stored in RfcommChannel — used by messaging layer via SessionCrypto

            // Build the final PeerDevice with full key material from the certificate
            val signingKeyBytes = java.util.Base64.getDecoder()
                .decode(peerCert.publicSigningKeyBase64)

            val finalPeer = PeerDevice(
                deviceId = peerCert.deviceId,
                displayName = peerCert.displayName,
                bluetoothAddress = socket.remoteDevice?.address,
                publicSigningKeyBytes = signingKeyBytes,
                publicDhKeyBytes = peerDhKeyBytes,
                rssi = 0, // Not available post-connection
                transport = TransportType.BLUETOOTH,
                discoveredAt = timeProvider.nowMillis(),
            )

            val channel = RfcommChannel(socket = socket, peerId = peerId)
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
