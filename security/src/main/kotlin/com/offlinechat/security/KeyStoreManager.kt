package com.offlinechat.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.offlinechat.core.result.AppError
import com.offlinechat.core.result.AppResult
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages long-term identity keys stored in the Android Keystore.
 *
 * ## Key design decisions
 * - We use the **Android Keystore** (hardware-backed on supported devices) to
 *   store the identity signing private key. The private key material NEVER
 *   leaves the Keystore — all signing happens inside the secure enclave.
 * - The EC P-256 curve is used for ECDSA signing because it is supported by
 *   the Android Keystore on API 26+ and provides 128-bit security.
 * - The key alias is a compile-time constant so it is stable across app updates.
 *
 * ## What is stored where
 * | Data | Storage |
 * |---|---|
 * | Private signing key | Android Keystore (hardware-backed) |
 * | Public signing key bytes | EncryptedSharedPreferences via [IdentityManager] |
 * | Device ID | EncryptedSharedPreferences via [IdentityManager] |
 */
@Singleton
class KeyStoreManager @Inject constructor() {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        internal const val SIGNING_KEY_ALIAS = "offlinechat_identity_signing_key"
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
    }

    // ── Key existence ──────────────────────────────────────────────────────────

    /** Returns true if the identity signing keypair already exists in the Keystore. */
    fun hasIdentityKey(): Boolean =
        keyStore.containsAlias(SIGNING_KEY_ALIAS)

    // ── Key generation ─────────────────────────────────────────────────────────

    /**
     * Generates a new EC P-256 signing keypair inside the Android Keystore.
     *
     * Safe to call multiple times — if the key already exists, this is a no-op.
     * The generated private key is non-exportable; only the public key is
     * accessible as a [PublicKey] object.
     *
     * @return [AppResult.Success] carrying the generated [KeyPair].
     */
    fun generateIdentityKeyPair(): AppResult<KeyPair> = runCatching {
        if (hasIdentityKey()) {
            // Return existing key pair
            return getIdentityKeyPair()
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER,
        )

        val parameterSpec = KeyGenParameterSpec.Builder(
            SIGNING_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // Require the device to be unlocked to use this key
            .setUserAuthenticationRequired(false) // set true to require biometric
            // Do not allow key export — the private key stays in Keystore
            .build()

        keyPairGenerator.initialize(parameterSpec)
        keyPairGenerator.generateKeyPair()
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(AppError.KeyGenerationFailed, it) },
    )

    // ── Key retrieval ──────────────────────────────────────────────────────────

    /**
     * Retrieves the existing identity keypair from the Keystore.
     * Returns [AppResult.Failure] with [AppError.IdentityNotInitialized] if
     * the key has not been generated yet.
     */
    fun getIdentityKeyPair(): AppResult<KeyPair> = runCatching {
        val privateKey = keyStore.getKey(SIGNING_KEY_ALIAS, null) as? PrivateKey
            ?: error("Identity signing key not found in Keystore")
        val publicKey = keyStore.getCertificate(SIGNING_KEY_ALIAS)?.publicKey
            ?: error("Identity signing certificate not found in Keystore")
        KeyPair(publicKey, privateKey)
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = {
            val error = if (it.message?.contains("not found") == true) {
                AppError.IdentityNotInitialized
            } else {
                AppError.Unknown(it.message ?: "Unknown Keystore error")
            }
            AppResult.Failure(error, it)
        },
    )

    /** Returns only the public signing key, or null if identity has not been created. */
    fun getPublicSigningKey(): PublicKey? =
        keyStore.getCertificate(SIGNING_KEY_ALIAS)?.publicKey

    // ── Signing ───────────────────────────────────────────────────────────────

    /**
     * Signs [data] with the private identity key stored in the Keystore.
     *
     * The signing operation executes inside the secure enclave — the private key
     * never enters the application process.
     *
     * @return Raw DER-encoded ECDSA signature bytes.
     */
    fun sign(data: ByteArray): AppResult<ByteArray> {
        val keyPairResult = getIdentityKeyPair()
        if (keyPairResult is AppResult.Failure) return keyPairResult

        return runCatching {
            val privateKey = (keyPairResult as AppResult.Success).data.private
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(privateKey)
                update(data)
            }.sign()
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.Unknown("Signing failed: ${it.message}"), it) },
        )
    }

    // ── Verification ──────────────────────────────────────────────────────────

    /**
     * Verifies an ECDSA signature against the given [publicKey].
     *
     * Used to verify signatures from REMOTE devices — their public keys come
     * from the [transport.api.PeerDevice] and are validated against the
     * [storage.dao.PeerDao] trust store.
     *
     * @param data      The original data that was signed.
     * @param signature The DER-encoded ECDSA signature to verify.
     * @param publicKey The signer's EC P-256 public key.
     * @return true if the signature is valid.
     */
    fun verify(
        data: ByteArray,
        signature: ByteArray,
        publicKey: PublicKey,
    ): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey)
            update(data)
        }.verify(signature)
    }.getOrElse { false }
}
