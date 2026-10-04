package com.offlinechat.domain.model

import kotlinx.serialization.Serializable

/**
 * Wire payload representation of an end-to-end encrypted message.
 *
 * Confidentiality & Integrity:
 * - Plaintext is never placed on the transport.
 * - Ciphertext is encrypted via AES-256-GCM with a 12-byte random nonce and 128-bit authentication tag.
 * - Digital signature is generated using the sender's Android Keystore private key over (sessionId + nonce + ciphertext).
 */
@Serializable
data class EncryptedMessagePayload(
    val sessionId: String,
    val nonceBase64: String,
    val ciphertextBase64: String,
    val signatureBase64: String,
    val senderFingerprint: String
)
