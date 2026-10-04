package com.offlinechat.security

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionCrypto @Inject constructor() {

    companion object {
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }

    private val secureRandom = SecureRandom()

    data class EncryptedResult(
        val ciphertext: ByteArray,
        val nonce: ByteArray
    )

    /**
     * Generates an ephemeral EC P-256 key pair for Diffie-Hellman session setup.
     */
    fun generateEphemeralKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    /**
     * Derives a symmetric AES-256 key from local private key and peer's public key via ECDH.
     */
    fun deriveSharedSecretKey(localPrivateKey: java.security.PrivateKey, peerPublicKeyBytes: ByteArray): SecretKey {
        val kf = KeyFactory.getInstance("EC")
        val peerKeySpec = X509EncodedKeySpec(peerPublicKeyBytes)
        val peerPublicKey = kf.generatePublic(peerKeySpec)

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(localPrivateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Use SHA-256 to hash the shared secret into an exact 256-bit AES key
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val aesKeyBytes = sha256.digest(sharedSecret)

        return SecretKeySpec(aesKeyBytes, "AES")
    }

    /**
     * Authenticated Encryption with Associated Data (AEAD) using AES-256-GCM.
     */
    fun encrypt(key: SecretKey, plaintext: ByteArray): EncryptedResult {
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedResult(ciphertext, nonce)
    }

    /**
     * Authenticated Decryption with Associated Data (AEAD) using AES-256-GCM.
     */
    fun decrypt(key: SecretKey, ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }
}
