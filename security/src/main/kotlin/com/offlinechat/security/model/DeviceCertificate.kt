package com.offlinechat.security.model

import kotlinx.serialization.Serializable

/**
 * A self-signed certificate that represents a device's public identity.
 *
 * This is the object broadcast during BLE advertising (encoded as bytes in the
 * manufacturer data) and exchanged during the RFCOMM handshake.  It contains
 * ONLY public key material — private keys are never serialised.
 *
 * ## Design
 * - This is a "TOFU certificate" — there is no Certificate Authority.
 * - [deviceId] is deterministically derived from [publicSigningKeyBase64] so
 *   a fake device cannot claim an identity it does not own.
 * - [signature] covers all other fields so the certificate cannot be tampered
 *   with in transit (replay/modification protection).
 * - [version] lets us evolve the format without breaking existing devices.
 *
 * @param version             Schema version, currently 1.
 * @param deviceId            Hex-encoded first 16 bytes of SHA-256(publicSigningKey).
 * @param displayName         Human-readable device name (up to 32 chars).
 * @param publicSigningKeyBase64  Base64-encoded EC P-256 public key (X.509 SubjectPublicKeyInfo).
 * @param publicDhKeyBase64   Base64-encoded ephemeral EC P-256 key used for ECDH this session.
 * @param issuedAt            Unix epoch ms when this certificate was generated.
 * @param signature           Base64-encoded ECDSA signature over the canonical byte string
 *                            of all other fields. Verified by the recipient.
 */
@Serializable
data class DeviceCertificate(
    val version: Int = CURRENT_VERSION,
    val deviceId: String,
    val displayName: String,
    val publicSigningKeyBase64: String,
    val publicDhKeyBase64: String,
    val issuedAt: Long,
    val signature: String,
) {
    companion object {
        const val CURRENT_VERSION = 1

        /**
         * Maximum display name length enforced before constructing a certificate.
         * Keeps BLE advertisement payloads within the 31-byte manufacturer data limit.
         */
        const val MAX_DISPLAY_NAME_LENGTH = 32

        /**
         * Produces the canonical byte sequence that is signed and verified.
         *
         * All signable fields are concatenated with a separator so that a field
         * value cannot "absorb" the separator from a neighbouring field (length-prefix
         * would be safer, but separator is sufficient for fixed-format keys).
         */
        fun signableBytes(
            version: Int,
            deviceId: String,
            displayName: String,
            publicSigningKeyBase64: String,
            publicDhKeyBase64: String,
            issuedAt: Long,
        ): ByteArray =
            "$version|$deviceId|$displayName|$publicSigningKeyBase64|$publicDhKeyBase64|$issuedAt"
                .toByteArray(Charsets.UTF_8)
    }

    /** Convenience: the bytes that were (or should be) signed. */
    fun signableBytes(): ByteArray = signableBytes(
        version, deviceId, displayName, publicSigningKeyBase64, publicDhKeyBase64, issuedAt,
    )
}
