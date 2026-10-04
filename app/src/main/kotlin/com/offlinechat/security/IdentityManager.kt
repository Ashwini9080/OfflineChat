package com.offlinechat.security

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityManager @Inject constructor(
    private val keystoreManager: KeystoreManager
) {
    /**
     * Stable, unique cryptographic device identity derived from the Keystore public key.
     * Guaranteed to be unforgeable and separate from display name.
     */
    val deviceId: String by lazy {
        val pubKeyBytes = keystoreManager.getPublicKey().encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(pubKeyBytes)
        digest.take(16).joinToString("") { "%02x".format(it) }
    }

    val publicKeyBytes: ByteArray
        get() = keystoreManager.getPublicKey().encoded

    fun signPayload(payload: ByteArray): ByteArray {
        return keystoreManager.sign(payload)
    }

    fun verifyPeerSignature(peerPublicKeyBytes: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(peerPublicKeyBytes))
            keystoreManager.verify(pubKey, payload, signature)
        } catch (e: Exception) {
            false
        }
    }
}
