package com.offlinechat.domain.model

import kotlinx.serialization.Serializable

/**
 * Payload exchanged immediately upon establishing an RFCOMM socket connection
 * to mutually verify cryptographic identities, execute ECDH key agreement,
 * and calculate Short Authentication Strings (Safety Numbers).
 */
@Serializable
data class HandshakePayload(
    val deviceId: String,
    val displayName: String,
    val publicKeyBase64: String,
    val ephemeralPublicKeyBase64: String,
    val signatureBase64: String,
    val identityFingerprint: String,
    val timestamp: Long = System.currentTimeMillis()
)
