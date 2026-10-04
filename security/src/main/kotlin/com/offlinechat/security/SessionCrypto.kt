package com.offlinechat.security

import com.offlinechat.core.result.AppError
import com.offlinechat.core.result.AppResult
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import java.security.KeyFactory
import java.security.SecureRandom
import javax.inject.Singleton

/**
 * Handles ephemeral session key exchange and authenticated encryption.
 *
 * ## Protocol overview
 *
 * Each connection between two devices goes through a two-phase handshake:
 *
 * ### Phase A — Key Exchange (ECDH)
 * 1. Both devices generate a fresh ephemeral EC P-256 keypair ([generateEphemeralDhKeyPair]).
 * 2. They exchange public DH keys via their [security.model.DeviceCertificate]s.
 * 3. Both sides compute the shared ECDH secret ([computeSharedSecret]).
 * 4. The raw ECDH output is fed through HKDF ([deriveSessionKey]) to produce a
 *    256-bit AES key. HKDF mixes in both device IDs as "info" so the same ECDH
 *    secret produces different keys in each direction.
 *
 * ### Phase B — Message Encryption (AES-256-GCM)
 * - Every [messaging.MessageEnvelope] payload is encrypted with [encrypt].
 * - Each call generates a fresh 12-byte random nonce. **Never reuse a nonce.**
 * - The ciphertext includes a 16-byte GCM authentication tag (automatic).
 * - [decrypt] rejects any ciphertext with a bad tag before returning plaintext.
 *
 * ## Forward secrecy
 * Ephemeral keypairs are held in memory only and discarded when the RFCOMM
 * connection closes. A later compromise of the long-term Keystore key does NOT
 * expose past session messages.
 *
 * ## Why not use Tink for ECDH?
 * Tink's hybrid encryption API manages both ECDH and AEAD together using an
 * opaque keyset format, which makes it harder to split the key exchange over
 * the BLE advertisement channel (public key in manufacturer data, actual
 * RFCOMM channel for data). Using standard Java crypto gives us precise
 * control over the wire protocol without losing any security properties.
 */
@Singleton
class SessionCrypto @Inject constructor() {

    companion object {
        private const val EC_ALGORITHM = "EC"
        private const val EC_CURVE = "secp256r1" // NIST P-256
        private const val ECDH_ALGORITHM = "ECDH"
        private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_NONCE_LENGTH_BYTES = 12
        private const val SESSION_KEY_LENGTH_BYTES = 32 // AES-256
        private const val HKDF_HMAC_ALGORITHM = "HmacSHA256"
        private const val HKDF_PROTOCOL_INFO = "offlinechat-session-key-v1"
    }

    // ── Ephemeral DH keypair ───────────────────────────────────────────────────

    /**
     * Generates a fresh EC P-256 keypair for one ECDH session.
     *
     * Call this once per incoming/outgoing connection. The returned [KeyPair]
     * must be kept in memory only — never persisted to disk or Keystore.
     * The public key is passed to [IdentityManager.issueCertificate] for
     * inclusion in the handshake [DeviceCertificate].
     */
    fun generateEphemeralDhKeyPair(): AppResult<KeyPair> = runCatching {
        KeyPairGenerator.getInstance(EC_ALGORITHM).apply {
            initialize(ECGenParameterSpec(EC_CURVE))
        }.generateKeyPair()
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(AppError.KeyGenerationFailed, it) },
    )

    // ── Shared secret computation (ECDH) ──────────────────────────────────────

    /**
     * Computes the raw ECDH shared secret from our ephemeral private key and
     * the peer's ephemeral public key.
     *
     * Do NOT use the raw output as a key directly — always pass it through
     * [deriveSessionKey] first.
     *
     * @param ourEphemeralPrivateKey  Our ephemeral private key from [generateEphemeralDhKeyPair].
     * @param peerEphemeralPublicKeyBytes  The peer's DH public key bytes from their
     *                                    [DeviceCertificate.publicDhKeyBase64] (decoded).
     */
    fun computeSharedSecret(
        ourEphemeralPrivateKey: java.security.PrivateKey,
        peerEphemeralPublicKeyBytes: ByteArray,
    ): AppResult<ByteArray> = runCatching {
        val peerPublicKey = KeyFactory.getInstance(EC_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(peerEphemeralPublicKeyBytes))

        KeyAgreement.getInstance(ECDH_ALGORITHM).apply {
            init(ourEphemeralPrivateKey)
            doPhase(peerPublicKey, true)
        }.generateSecret()
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(AppError.KeyGenerationFailed, it) },
    )

    // ── Session key derivation (HKDF) ─────────────────────────────────────────

    /**
     * Derives a 256-bit AES session key from the raw ECDH shared secret using HKDF.
     *
     * HKDF (RFC 5869) is used rather than using the raw ECDH output directly
     * because:
     * - ECDH output has non-uniform distribution; HKDF normalises it.
     * - The "info" parameter binds the key to a specific protocol context and
     *   direction, preventing cross-protocol key reuse.
     *
     * @param sharedSecret  Raw ECDH output from [computeSharedSecret].
     * @param initiatorId   [DeviceIdentity.id] of the device that initiated the connection.
     * @param responderId   [DeviceIdentity.id] of the device that accepted the connection.
     */
    fun deriveSessionKey(
        sharedSecret: ByteArray,
        initiatorId: String,
        responderId: String,
    ): AppResult<SecretKey> = runCatching {
        // HKDF-Extract: HMAC-SHA256(salt=zeros, IKM=sharedSecret)
        val salt = ByteArray(32) // all-zero salt is fine per RFC 5869 §3.1
        val prk = hmacSha256(key = salt, data = sharedSecret)

        // HKDF-Expand: HMAC-SHA256(PRK, info || 0x01) truncated to 32 bytes
        val info = "$HKDF_PROTOCOL_INFO|$initiatorId|$responderId".toByteArray(Charsets.UTF_8)
        val okmInput = info + byteArrayOf(0x01)
        val okm = hmacSha256(key = prk, data = okmInput)

        SecretKeySpec(okm.copyOf(SESSION_KEY_LENGTH_BYTES), "AES")
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(AppError.KeyGenerationFailed, it) },
    )

    // ── AES-256-GCM encryption ────────────────────────────────────────────────

    /**
     * Encrypts [plaintext] under [sessionKey] using AES-256-GCM.
     *
     * A fresh 12-byte nonce is generated for every call using [SecureRandom].
     * The nonce is prepended to the ciphertext so the receiver can extract it.
     *
     * Wire format: `[ 12-byte nonce | GCM ciphertext (includes 16-byte tag) ]`
     *
     * @param sessionKey    The 256-bit AES key from [deriveSessionKey].
     * @param plaintext     The serialised [messaging.MessageEnvelope] payload.
     * @param associatedData Optional AAD bound to this ciphertext (e.g., envelope ID bytes).
     *                       The same [associatedData] MUST be supplied to [decrypt].
     * @return nonce + ciphertext as a single [ByteArray].
     */
    fun encrypt(
        sessionKey: SecretKey,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): AppResult<ByteArray> = runCatching {
        val nonce = ByteArray(GCM_NONCE_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            sessionKey,
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce),
        )
        if (associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }
        val ciphertext = cipher.doFinal(plaintext)

        // Prepend nonce so the receiver can extract it
        nonce + ciphertext
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(AppError.Unknown("Encryption failed: ${it.message}"), it) },
    )

    /**
     * Decrypts a [SessionCrypto.encrypt] output.
     *
     * The nonce is extracted from the first 12 bytes. GCM tag verification is
     * automatic — if it fails, [AppError.DecryptionFailed] is returned and NO
     * plaintext is exposed. This prevents padding oracle and other chosen-ciphertext
     * attacks.
     *
     * @param sessionKey     Must be the same key used during [encrypt].
     * @param nonceAndCiphertext  The raw wire bytes (nonce + ciphertext + tag).
     * @param associatedData Must match the [associatedData] used during [encrypt].
     */
    fun decrypt(
        sessionKey: SecretKey,
        nonceAndCiphertext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): AppResult<ByteArray> = runCatching {
        require(nonceAndCiphertext.size > GCM_NONCE_LENGTH_BYTES) {
            "Ciphertext too short to contain nonce"
        }

        val nonce = nonceAndCiphertext.copyOf(GCM_NONCE_LENGTH_BYTES)
        val ciphertext = nonceAndCiphertext.copyOfRange(GCM_NONCE_LENGTH_BYTES, nonceAndCiphertext.size)

        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            sessionKey,
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce),
        )
        if (associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }
        cipher.doFinal(ciphertext)
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(AppError.DecryptionFailed, it) },
    )

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HKDF_HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HKDF_HMAC_ALGORITHM))
        return mac.doFinal(data)
    }
}
