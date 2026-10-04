package com.offlinechat.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.offlinechat.core.model.DeviceIdentity
import com.offlinechat.core.result.AppError
import com.offlinechat.core.result.AppResult
import com.offlinechat.core.util.TimeProvider
import com.offlinechat.security.model.DeviceCertificate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the device's long-term cryptographic identity.
 *
 * ## Responsibilities
 * 1. First-launch: generate EC P-256 keypair in Android Keystore via [KeyStoreManager].
 * 2. Derive a stable, unforgeable [DeviceIdentity.id] from the public key.
 * 3. Persist the public key and device ID in [EncryptedSharedPreferences].
 * 4. Expose [getLocalIdentity] for use by the transport layer during advertising.
 * 5. Issue [DeviceCertificate]s for outbound handshakes.
 * 6. Verify incoming [DeviceCertificate]s from peers (signature check + ID derivation check).
 *
 * ## Thread safety
 * All public functions are safe to call from any thread. Preference writes
 * are synchronous but lightweight (small byte arrays).
 */
@Singleton
class IdentityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStoreManager: KeyStoreManager,
    private val timeProvider: TimeProvider,
) {
    companion object {
        private const val PREFS_FILE_NAME = "offlinechat_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_SIGNING_KEY_B64 = "public_signing_key_b64"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_CREATED_AT = "created_at"
    }

    // Lazy-init to defer I/O until first use
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── Initialisation ─────────────────────────────────────────────────────────

    /**
     * Ensures the device identity exists.
     *
     * Safe to call on every app launch. It is idempotent:
     * - If an identity already exists (prefs + Keystore key), returns it immediately.
     * - If not, generates keys, derives the device ID, and persists everything.
     *
     * This should be called early in the app lifecycle (e.g., in Application.onCreate
     * via Hilt) so that any failure is surfaced before the user tries to chat.
     *
     * @param desiredDisplayName Optional display name. Only used on first launch.
     *                           Subsequent calls ignore this parameter.
     */
    suspend fun ensureIdentityExists(desiredDisplayName: String? = null): AppResult<DeviceIdentity> {
        // Fast path: identity already initialised
        if (prefs.contains(KEY_DEVICE_ID) && keyStoreManager.hasIdentityKey()) {
            return getLocalIdentity()
        }

        // Generate keypair in Keystore
        val keyPairResult = keyStoreManager.generateIdentityKeyPair()
        if (keyPairResult is AppResult.Failure) return keyPairResult

        val publicKey = (keyPairResult as AppResult.Success).data.public
        val publicKeyBytes = publicKey.encoded // X.509 SubjectPublicKeyInfo encoding

        // Derive device ID: first 16 bytes of SHA-256(publicKey) → hex string
        val sha256 = MessageDigest.getInstance("SHA-256")
        val deviceId = sha256.digest(publicKeyBytes)
            .take(16)
            .joinToString("") { "%02x".format(it) }

        val displayName = desiredDisplayName?.take(DeviceCertificate.MAX_DISPLAY_NAME_LENGTH)
            ?: android.os.Build.MODEL.take(DeviceCertificate.MAX_DISPLAY_NAME_LENGTH)

        val publicKeyB64 = Base64.getEncoder().encodeToString(publicKeyBytes)
        val createdAt = timeProvider.nowMillis()

        // Persist to encrypted prefs
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_PUBLIC_SIGNING_KEY_B64, publicKeyB64)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putLong(KEY_CREATED_AT, createdAt)
            .apply()

        return AppResult.Success(
            DeviceIdentity(
                id = deviceId,
                displayName = displayName,
                publicSigningKey = publicKeyBytes,
                publicDhKey = ByteArray(0), // DH key is ephemeral per-session; see SessionCrypto
                createdAt = createdAt,
            ),
        )
    }

    // ── Identity access ────────────────────────────────────────────────────────

    /**
     * Returns the local device's persisted identity.
     *
     * Call [ensureIdentityExists] before this; if identity has not been created,
     * this returns [AppResult.Failure] with [AppError.IdentityNotInitialized].
     */
    fun getLocalIdentity(): AppResult<DeviceIdentity> {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
            ?: return AppResult.Failure(AppError.IdentityNotInitialized)
        val publicKeyB64 = prefs.getString(KEY_PUBLIC_SIGNING_KEY_B64, null)
            ?: return AppResult.Failure(AppError.IdentityNotInitialized)
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null)
            ?: return AppResult.Failure(AppError.IdentityNotInitialized)
        val createdAt = prefs.getLong(KEY_CREATED_AT, 0L)

        val publicKeyBytes = Base64.getDecoder().decode(publicKeyB64)

        return AppResult.Success(
            DeviceIdentity(
                id = deviceId,
                displayName = displayName,
                publicSigningKey = publicKeyBytes,
                publicDhKey = ByteArray(0), // Ephemeral per-session
                createdAt = createdAt,
            ),
        )
    }

    // ── Certificate issuance ───────────────────────────────────────────────────

    /**
     * Creates a signed [DeviceCertificate] to send to a peer during the handshake.
     *
     * @param ephemeralDhPublicKey The session's ephemeral EC P-256 DH public key,
     *                             generated by [SessionCrypto] for this connection.
     */
    fun issueCertificate(ephemeralDhPublicKey: PublicKey): AppResult<DeviceCertificate> {
        val identityResult = getLocalIdentity()
        if (identityResult is AppResult.Failure) return identityResult
        val identity = (identityResult as AppResult.Success).data

        val signingKeyB64 = Base64.getEncoder().encodeToString(identity.publicSigningKey)
        val dhKeyB64 = Base64.getEncoder().encodeToString(ephemeralDhPublicKey.encoded)
        val issuedAt = timeProvider.nowMillis()

        val signableBytes = DeviceCertificate.signableBytes(
            version = DeviceCertificate.CURRENT_VERSION,
            deviceId = identity.id,
            displayName = identity.displayName,
            publicSigningKeyBase64 = signingKeyB64,
            publicDhKeyBase64 = dhKeyB64,
            issuedAt = issuedAt,
        )

        val signatureResult = keyStoreManager.sign(signableBytes)
        if (signatureResult is AppResult.Failure) return signatureResult

        val signatureB64 = Base64.getEncoder()
            .encodeToString((signatureResult as AppResult.Success).data)

        return AppResult.Success(
            DeviceCertificate(
                deviceId = identity.id,
                displayName = identity.displayName,
                publicSigningKeyBase64 = signingKeyB64,
                publicDhKeyBase64 = dhKeyB64,
                issuedAt = issuedAt,
                signature = signatureB64,
            ),
        )
    }

    // ── Certificate verification ───────────────────────────────────────────────

    /**
     * Verifies a [DeviceCertificate] received from a peer.
     *
     * Verification steps:
     * 1. Decode the claimed public signing key.
     * 2. Check that [DeviceCertificate.deviceId] matches the key derivation
     *    (hex(SHA-256(publicKey))[0..31]) — prevents identity spoofing.
     * 3. Verify the ECDSA signature over the canonical bytes.
     * 4. Reject certificates that are unreasonably old (>10 minutes) to limit
     *    replay window.
     *
     * @return [AppResult.Success] with the same certificate on success.
     *         [AppResult.Failure] with [AppError.SignatureVerificationFailed]
     *         or [AppError.Unknown] on any validation error.
     */
    fun verifyCertificate(certificate: DeviceCertificate): AppResult<DeviceCertificate> {
        return runCatching {
            // 1. Decode public key
            val signingKeyBytes = Base64.getDecoder().decode(certificate.publicSigningKeyBase64)
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(
                java.security.spec.X509EncodedKeySpec(signingKeyBytes),
            )

            // 2. Verify device ID derivation
            val sha256 = MessageDigest.getInstance("SHA-256")
            val expectedDeviceId = sha256.digest(signingKeyBytes)
                .take(16)
                .joinToString("") { "%02x".format(it) }

            require(expectedDeviceId == certificate.deviceId) {
                "Device ID does not match public key derivation"
            }

            // 3. Verify signature
            val signatureBytes = Base64.getDecoder().decode(certificate.signature)
            val valid = keyStoreManager.verify(
                data = certificate.signableBytes(),
                signature = signatureBytes,
                publicKey = publicKey,
            )
            require(valid) { "ECDSA signature verification failed" }

            // 4. Replay window check: reject if issued more than 10 minutes ago
            val ageMs = timeProvider.nowMillis() - certificate.issuedAt
            require(ageMs < 10 * 60 * 1000L) {
                "Certificate is stale (issued ${ageMs}ms ago)"
            }

            certificate
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.SignatureVerificationFailed, it) },
        )
    }

    // ── Display name management ────────────────────────────────────────────────

    /**
     * Updates the device's display name.
     *
     * The new name will appear in future [DeviceCertificate]s issued to peers.
     * Existing peers will see the old name until they reconnect and receive a
     * fresh certificate.
     */
    fun updateDisplayName(name: String): AppResult<Unit> = runCatching {
        val trimmed = name.trim().take(DeviceCertificate.MAX_DISPLAY_NAME_LENGTH)
        require(trimmed.isNotEmpty()) { "Display name must not be empty" }
        prefs.edit().putString(KEY_DISPLAY_NAME, trimmed).apply()
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Failure(AppError.Unknown(it.message ?: "Failed to update name"), it) },
    )
}
