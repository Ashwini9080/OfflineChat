package com.offlinechat.domain.model

import kotlinx.serialization.Serializable

/**
 * Payload exchanged immediately upon establishing an RFCOMM socket connection
 * to mutually verify cryptographic identities and update contact information.
 */
@Serializable
data class HandshakePayload(
    val deviceId: String,
    val displayName: String,
    val publicKeyBase64: String,
    val timestamp: Long = System.currentTimeMillis()
)
