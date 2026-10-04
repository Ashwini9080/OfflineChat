package com.offlinechat.security

import com.offlinechat.domain.repository.PeerRepository
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageSecurityImpl @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val sessionCrypto: SessionCrypto,
    private val peerRepository: PeerRepository
) : MessageSecurity {

    companion object {
        private const val NONCE_LENGTH = 12
        private const val MIN_PAYLOAD_LENGTH = NONCE_LENGTH + 16 // 12-byte nonce + 16-byte GCM auth tag
    }

    // Thread-safe in-memory cache of derived peer session keys
    private val sessionKeys = ConcurrentHashMap<String, SecretKey>()

    override fun getLocalPublicKey(): ByteArray {
        return keystoreManager.getPublicKey().encoded
    }

    override fun sign(data: ByteArray): ByteArray {
        return keystoreManager.sign(data)
    }

    override fun verify(peerPublicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(peerPublicKeyBytes))
            keystoreManager.verify(pubKey, data, signature)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Establishes or returns an active symmetric AES-256-GCM session key with a peer.
     * Uses ECDH key agreement between local private key and peer's public key.
     */
    fun getOrCreateSessionKey(peerId: String, peerPublicKeyBytes: ByteArray): SecretKey {
        return sessionKeys.computeIfAbsent(peerId) {
            val localKeyPair = keystoreManager.getOrCreateIdentityKeyPair()
            sessionCrypto.deriveSharedSecretKey(localKeyPair.private, peerPublicKeyBytes)
        }
    }

    override fun hasEstablishedSession(peerId: String): Boolean {
        return sessionKeys.containsKey(peerId)
    }

    override suspend fun encrypt(plaintext: ByteArray, recipientPeerId: String): ByteArray {
        val secretKey = sessionKeys[recipientPeerId] ?: run {
            // Attempt to derive from persisted peer public key if available
            val peer = peerRepository.getPeerById(recipientPeerId)
            if (peer != null && peer.publicKeyBytes.isNotEmpty()) {
                getOrCreateSessionKey(recipientPeerId, peer.publicKeyBytes)
            } else {
                throw IllegalStateException("Cannot encrypt: No established session or public key for peer: $recipientPeerId")
            }
        }

        val result = sessionCrypto.encrypt(secretKey, plaintext)
        val buffer = ByteBuffer.allocate(NONCE_LENGTH + result.ciphertext.size)
        buffer.put(result.nonce)
        buffer.put(result.ciphertext)
        return buffer.array()
    }

    override suspend fun decrypt(ciphertext: ByteArray, senderPeerId: String): ByteArray {
        if (ciphertext.size < MIN_PAYLOAD_LENGTH) {
            throw IllegalArgumentException("Malformed ciphertext: payload length too short")
        }

        val secretKey = sessionKeys[senderPeerId] ?: run {
            val peer = peerRepository.getPeerById(senderPeerId)
            if (peer != null && peer.publicKeyBytes.isNotEmpty()) {
                getOrCreateSessionKey(senderPeerId, peer.publicKeyBytes)
            } else {
                throw IllegalStateException("Cannot decrypt: No established session or public key for peer: $senderPeerId")
            }
        }

        val nonce = ByteArray(NONCE_LENGTH)
        val encryptedData = ByteArray(ciphertext.size - NONCE_LENGTH)

        val buffer = ByteBuffer.wrap(ciphertext)
        buffer.get(nonce)
        buffer.get(encryptedData)

        return sessionCrypto.decrypt(secretKey, encryptedData, nonce)
    }
}
