package com.offlinechat.security

import android.util.Log
import com.offlinechat.domain.model.EncryptedMessagePayload
import com.offlinechat.domain.model.HandshakePayload
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerTrustState
import com.offlinechat.domain.model.TransportType
import com.offlinechat.domain.repository.PeerRepository
import java.security.KeyPair
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageSecurityImpl @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val identityManager: IdentityManager,
    private val sessionCrypto: SessionCrypto,
    private val peerRepository: PeerRepository
) : MessageSecurity {

    companion object {
        private const val TAG = "MessageSecurity"
    }

    data class PeerSession(
        val sessionId: String,
        val sessionKey: SecretKey,
        val safetyNumber: String,
        val peerPublicKeyBytes: ByteArray,
        val establishedAt: Long = System.currentTimeMillis()
    )

    // Ephemeral key pair generated per local device lifecycle/session
    private val ephemeralKeyPair: KeyPair by lazy {
        sessionCrypto.generateEphemeralKeyPair()
    }

    // Active established sessions: peerId -> PeerSession
    private val activeSessions = ConcurrentHashMap<String, PeerSession>()

    override fun getLocalPublicKey(): ByteArray {
        return identityManager.publicKeyBytes
    }

    override fun getLocalDeviceFingerprint(): String {
        return identityManager.fingerprint
    }

    override fun createHandshakePayload(localDisplayName: String): HandshakePayload {
        val deviceId = identityManager.deviceId
        val pubKeyB64 = Base64.getEncoder().encodeToString(identityManager.publicKeyBytes)
        val ephemeralB64 = Base64.getEncoder().encodeToString(ephemeralKeyPair.public.encoded)
        val timestamp = System.currentTimeMillis()

        // Sign authentication binding: deviceId|publicKey|ephemeralKey|timestamp
        val dataToSign = "$deviceId|$pubKeyB64|$ephemeralB64|$timestamp".toByteArray(Charsets.UTF_8)
        val signatureBytes = identityManager.signPayload(dataToSign)
        val signatureB64 = Base64.getEncoder().encodeToString(signatureBytes)

        return HandshakePayload(
            deviceId = deviceId,
            displayName = localDisplayName,
            publicKeyBase64 = pubKeyB64,
            ephemeralPublicKeyBase64 = ephemeralB64,
            signatureBase64 = signatureB64,
            identityFingerprint = identityManager.fingerprint,
            timestamp = timestamp
        )
    }

    override suspend fun processHandshake(payload: HandshakePayload): HandshakeResult {
        val peerIdentityPubBytes = try {
            Base64.getDecoder().decode(payload.publicKeyBase64)
        } catch (e: Exception) {
            throw SecurityException("Invalid peer public identity key in handshake", e)
        }

        val peerEphemeralPubBytes = try {
            Base64.getDecoder().decode(payload.ephemeralPublicKeyBase64)
        } catch (e: Exception) {
            throw SecurityException("Invalid peer ephemeral key in handshake", e)
        }

        val signatureBytes = try {
            Base64.getDecoder().decode(payload.signatureBase64)
        } catch (e: Exception) {
            throw SecurityException("Invalid digital signature in handshake", e)
        }

        // 1. Authenticate digital signature
        val signedData = "${payload.deviceId}|${payload.publicKeyBase64}|${payload.ephemeralPublicKeyBase64}|${payload.timestamp}".toByteArray(Charsets.UTF_8)
        val isValidSig = identityManager.verifyPeerSignature(peerIdentityPubBytes, signedData, signatureBytes)
        if (!isValidSig) {
            Log.e(TAG, "Digital signature verification failed for peer ${payload.deviceId}")
            throw SecurityException("Peer handshake signature verification failed")
        }

        // 2. Validate cryptographic device identity hash
        val derivedId = IdentityManager.deriveDeviceId(peerIdentityPubBytes)
        if (derivedId != payload.deviceId) {
            Log.e(TAG, "Device ID does not match public key hash: expected $derivedId, got ${payload.deviceId}")
            throw SecurityException("Cryptographic device identity hash mismatch")
        }

        // 3. Check for Identity Replacement Attack against persisted peer
        val existingPeer = peerRepository.getPeerById(payload.deviceId)
        var isIdentityChanged = false
        var trustState: PeerTrustState

        if (existingPeer != null && existingPeer.publicKeyBytes.isNotEmpty()) {
            if (!existingPeer.publicKeyBytes.contentEquals(peerIdentityPubBytes)) {
                // Identity changed unexpectedly! Potential Man-in-the-Middle or key replacement attack
                Log.w(TAG, "SECURITY ALERT: Peer ${payload.deviceId} presented a different identity key than stored!")
                isIdentityChanged = true
                trustState = PeerTrustState.REVOKED
            } else {
                trustState = existingPeer.trustState
                if (trustState == PeerTrustState.UNKNOWN) {
                    trustState = PeerTrustState.CONNECTED
                }
            }
        } else {
            // First time seeing this peer
            trustState = PeerTrustState.VERIFICATION_REQUIRED
        }

        // 4. Execute ECDH key agreement and HKDF-SHA256 session derivation
        val sessionMaterial = sessionCrypto.deriveSessionMaterial(
            localPrivateKey = ephemeralKeyPair.private,
            peerPublicKeyBytes = peerEphemeralPubBytes,
            localIdentityPubBytes = identityManager.publicKeyBytes,
            peerIdentityPubBytes = peerIdentityPubBytes
        )

        val sessionId = UUID.randomUUID().toString()
        val session = PeerSession(
            sessionId = sessionId,
            sessionKey = sessionMaterial.sessionKey,
            safetyNumber = sessionMaterial.safetyNumber,
            peerPublicKeyBytes = peerIdentityPubBytes
        )
        activeSessions[payload.deviceId] = session

        // 5. Persist peer in local Room database with cryptographic metadata
        val peerFingerprint = IdentityManager.computeFingerprint(peerIdentityPubBytes)
        val updatedPeer = Peer(
            deviceId = payload.deviceId,
            displayName = payload.displayName,
            bluetoothAddress = existingPeer?.bluetoothAddress,
            publicKeyBytes = peerIdentityPubBytes,
            rssi = existingPeer?.rssi ?: -100,
            isTrusted = (trustState == PeerTrustState.VERIFIED),
            isConnected = true,
            transportType = existingPeer?.transportType ?: TransportType.BLUETOOTH,
            lastSeenAt = System.currentTimeMillis(),
            trustState = trustState,
            safetyNumber = sessionMaterial.safetyNumber,
            identityFingerprint = peerFingerprint
        )
        peerRepository.saveOrUpdatePeer(updatedPeer)

        Log.i(TAG, "Established encrypted session with ${payload.displayName} [${payload.deviceId}], Trust: $trustState, SAS: ${sessionMaterial.safetyNumber}")

        return HandshakeResult(
            peerId = payload.deviceId,
            peerDisplayName = payload.displayName,
            trustState = trustState,
            safetyNumber = sessionMaterial.safetyNumber,
            isIdentityChanged = isIdentityChanged
        )
    }

    override suspend fun encryptMessage(
        plaintext: ByteArray,
        recipientPeerId: String,
        messageId: String
    ): EncryptedMessagePayload {
        val currentTrustState = getPeerTrustState(recipientPeerId)
        if (currentTrustState == PeerTrustState.REVOKED) {
            throw SecurityException("Transmission blocked: Peer identity is REVOKED due to key change or security policy.")
        }

        val session = activeSessions[recipientPeerId] ?: run {
            // If session not in memory but peer public key is in Room, derive session
            val peer = peerRepository.getPeerById(recipientPeerId)
            if (peer != null && peer.publicKeyBytes.isNotEmpty()) {
                val material = sessionCrypto.deriveSessionMaterial(
                    localPrivateKey = keystoreManager.getOrCreateIdentityKeyPair().private,
                    peerPublicKeyBytes = peer.publicKeyBytes,
                    localIdentityPubBytes = identityManager.publicKeyBytes,
                    peerIdentityPubBytes = peer.publicKeyBytes
                )
                val newSession = PeerSession(
                    sessionId = UUID.randomUUID().toString(),
                    sessionKey = material.sessionKey,
                    safetyNumber = material.safetyNumber,
                    peerPublicKeyBytes = peer.publicKeyBytes
                )
                activeSessions[recipientPeerId] = newSession
                newSession
            } else {
                throw SecurityException("No established cryptographic session for peer: $recipientPeerId")
            }
        }

        // Authenticated Associated Data binds session + messageId
        val associatedData = "${session.sessionId}:$messageId".toByteArray(Charsets.UTF_8)
        val encResult = sessionCrypto.encrypt(session.sessionKey, plaintext, associatedData)

        // Sign (sessionId + nonce + ciphertext)
        val dataToSign = session.sessionId.toByteArray(Charsets.UTF_8) + encResult.nonce + encResult.ciphertext
        val signatureBytes = identityManager.signPayload(dataToSign)

        return EncryptedMessagePayload(
            sessionId = session.sessionId,
            nonceBase64 = Base64.getEncoder().encodeToString(encResult.nonce),
            ciphertextBase64 = Base64.getEncoder().encodeToString(encResult.ciphertext),
            signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes),
            senderFingerprint = identityManager.fingerprint
        )
    }

    override suspend fun decryptMessage(
        encryptedPayload: EncryptedMessagePayload,
        senderPeerId: String,
        messageId: String
    ): ByteArray {
        val currentTrustState = getPeerTrustState(senderPeerId)
        if (currentTrustState == PeerTrustState.REVOKED) {
            throw SecurityException("Decryption blocked: Sender cryptographic identity has been REVOKED.")
        }

        val session = activeSessions[senderPeerId] ?: run {
            val peer = peerRepository.getPeerById(senderPeerId)
            if (peer != null && peer.publicKeyBytes.isNotEmpty()) {
                val material = sessionCrypto.deriveSessionMaterial(
                    localPrivateKey = keystoreManager.getOrCreateIdentityKeyPair().private,
                    peerPublicKeyBytes = peer.publicKeyBytes,
                    localIdentityPubBytes = identityManager.publicKeyBytes,
                    peerIdentityPubBytes = peer.publicKeyBytes
                )
                val newSession = PeerSession(
                    sessionId = encryptedPayload.sessionId,
                    sessionKey = material.sessionKey,
                    safetyNumber = material.safetyNumber,
                    peerPublicKeyBytes = peer.publicKeyBytes
                )
                activeSessions[senderPeerId] = newSession
                newSession
            } else {
                throw SecurityException("No established cryptographic session for sender: $senderPeerId")
            }
        }

        val nonce = try {
            Base64.getDecoder().decode(encryptedPayload.nonceBase64)
        } catch (e: Exception) {
            throw SecurityException("Malformed nonce in encrypted message", e)
        }

        val ciphertext = try {
            Base64.getDecoder().decode(encryptedPayload.ciphertextBase64)
        } catch (e: Exception) {
            throw SecurityException("Malformed ciphertext in encrypted message", e)
        }

        val signature = try {
            Base64.getDecoder().decode(encryptedPayload.signatureBase64)
        } catch (e: Exception) {
            throw SecurityException("Malformed digital signature in encrypted message", e)
        }

        // 1. Verify digital signature over (sessionId + nonce + ciphertext)
        val dataToVerify = encryptedPayload.sessionId.toByteArray(Charsets.UTF_8) + nonce + ciphertext
        val isSigValid = identityManager.verifyPeerSignature(session.peerPublicKeyBytes, dataToVerify, signature)
        if (!isSigValid) {
            Log.e(TAG, "Message signature authentication failed for sender: $senderPeerId")
            throw SecurityException("Message sender authentication failed: Invalid signature")
        }

        // 2. Authenticated Decryption with Associated Data
        val associatedData = "${encryptedPayload.sessionId}:$messageId".toByteArray(Charsets.UTF_8)
        return try {
            sessionCrypto.decrypt(session.sessionKey, ciphertext, nonce, associatedData)
        } catch (e: Exception) {
            Log.e(TAG, "AEAD integrity check failed: Ciphertext has been tampered with or corrupted", e)
            throw SecurityException("Ciphertext integrity verification failed", e)
        }
    }

    override suspend fun getPeerTrustState(peerId: String): PeerTrustState {
        val peer = peerRepository.getPeerById(peerId)
        return peer?.trustState ?: PeerTrustState.UNKNOWN
    }

    override suspend fun verifyPeer(peerId: String) {
        val safetyNumber = getSafetyNumber(peerId)
        peerRepository.updateTrustState(peerId, PeerTrustState.VERIFIED, safetyNumber)
        Log.i(TAG, "Peer $peerId marked as VERIFIED")
    }

    override suspend fun revokePeer(peerId: String) {
        val safetyNumber = getSafetyNumber(peerId)
        peerRepository.updateTrustState(peerId, PeerTrustState.REVOKED, safetyNumber)
        activeSessions.remove(peerId)
        Log.w(TAG, "Peer $peerId has been REVOKED")
    }

    override fun getSafetyNumber(peerId: String): String? {
        return activeSessions[peerId]?.safetyNumber
    }

    override fun hasEstablishedSession(peerId: String): Boolean {
        return activeSessions.containsKey(peerId)
    }

    override fun sign(data: ByteArray): ByteArray {
        return identityManager.signPayload(data)
    }

    override fun verify(peerPublicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return identityManager.verifyPeerSignature(peerPublicKeyBytes, data, signature)
    }
}
