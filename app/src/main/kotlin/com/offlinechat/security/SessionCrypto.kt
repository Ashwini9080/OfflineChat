package com.offlinechat.security

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
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
import kotlin.math.abs

@Singleton
class SessionCrypto @Inject constructor() {

    companion object {
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private val INFO_SESSION_KEY = "OfflineChat-AES-256-GCM-Session".toByteArray(Charsets.UTF_8)
        private val INFO_SAFETY_NUMBER = "OfflineChat-Safety-Number-SAS".toByteArray(Charsets.UTF_8)
    }

    private val secureRandom = SecureRandom()

    data class EncryptedResult(
        val ciphertext: ByteArray,
        val nonce: ByteArray
    )

    data class SessionMaterial(
        val sessionKey: SecretKey,
        val safetyNumber: String,
        val sharedSecret: ByteArray
    )

    /**
     * Generates an ephemeral EC P-256 key pair for forward secrecy session setup.
     */
    fun generateEphemeralKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    /**
     * Executes ECDH between local private key and peer's public key.
     */
    fun computeDiffieHellmanSharedSecret(localPrivateKey: PrivateKey, peerPublicKeyBytes: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("EC")
        val peerKeySpec = X509EncodedKeySpec(peerPublicKeyBytes)
        val peerPublicKey = kf.generatePublic(peerKeySpec)

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(localPrivateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        return keyAgreement.generateSecret()
    }

    /**
     * Derives session key and Short Authentication String (Safety Number)
     * using RFC 5869 HKDF-SHA256 over the ECDH shared secret.
     */
    fun deriveSessionMaterial(
        localPrivateKey: PrivateKey,
        peerPublicKeyBytes: ByteArray,
        localIdentityPubBytes: ByteArray,
        peerIdentityPubBytes: ByteArray
    ): SessionMaterial {
        val sharedSecret = computeDiffieHellmanSharedSecret(localPrivateKey, peerPublicKeyBytes)

        // 1. Derive 256-bit AES-GCM session key
        val derivedKeyBytes = Hkdf.deriveKey(
            salt = null,
            ikm = sharedSecret,
            info = INFO_SESSION_KEY,
            length = 32
        )
        val sessionKey = SecretKeySpec(derivedKeyBytes, "AES")

        // 2. Derive cryptographically sound Short Authentication String (Safety Number)
        val safetyNumber = computeSafetyNumber(localIdentityPubBytes, peerIdentityPubBytes, sharedSecret)

        return SessionMaterial(
            sessionKey = sessionKey,
            safetyNumber = safetyNumber,
            sharedSecret = sharedSecret
        )
    }

    /**
     * Calculates a 6-digit numeric Short Authentication String (Safety Number)
     * formatted as "### ###" (e.g., "482 913").
     *
     * Sorting the public keys lexicographically ensures both peers produce
     * the exact same verification code regardless of who initiated the connection.
     */
    fun computeSafetyNumber(
        identityKeyA: ByteArray,
        identityKeyB: ByteArray,
        sharedSecret: ByteArray
    ): String {
        // Sort keys lexicographically for symmetry
        val (firstKey, secondKey) = if (compareByteArrays(identityKeyA, identityKeyB) <= 0) {
            identityKeyA to identityKeyB
        } else {
            identityKeyB to identityKeyA
        }

        val salt = ByteArray(firstKey.size + secondKey.size)
        System.arraycopy(firstKey, 0, salt, 0, firstKey.size)
        System.arraycopy(secondKey, 0, salt, firstKey.size, secondKey.size)

        val sasBytes = Hkdf.deriveKey(
            salt = salt,
            ikm = sharedSecret,
            info = INFO_SAFETY_NUMBER,
            length = 4
        )

        val intVal = abs(ByteBuffer.wrap(sasBytes).int) % 1_000_000
        val part1 = intVal / 1000
        val part2 = intVal % 1000
        return "%03d %03d".format(part1, part2)
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val byteA = a[i].toInt() and 0xFF
            val byteB = b[i].toInt() and 0xFF
            if (byteA != byteB) return byteA.compareTo(byteB)
        }
        return a.size.compareTo(b.size)
    }

    /**
     * Authenticated Encryption with Associated Data (AEAD) using AES-256-GCM.
     * Enforces unique 12-byte random nonce per encryption operation.
     */
    fun encrypt(key: SecretKey, plaintext: ByteArray, associatedData: ByteArray? = null): EncryptedResult {
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        if (associatedData != null && associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedResult(ciphertext, nonce)
    }

    /**
     * Authenticated Decryption with Associated Data (AEAD) using AES-256-GCM.
     * Validates 128-bit authentication tag before returning plaintext.
     */
    fun decrypt(key: SecretKey, ciphertext: ByteArray, nonce: ByteArray, associatedData: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        if (associatedData != null && associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }
        return cipher.doFinal(ciphertext)
    }
}
