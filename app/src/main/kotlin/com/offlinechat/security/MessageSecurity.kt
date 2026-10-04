package com.offlinechat.security

import com.offlinechat.domain.model.EncryptedMessagePayload
import com.offlinechat.domain.model.HandshakePayload
import com.offlinechat.domain.model.PeerTrustState

data class HandshakeResult(
    val peerId: String,
    val peerDisplayName: String,
    val trustState: PeerTrustState,
    val safetyNumber: String,
    val isIdentityChanged: Boolean
)

/**
 * High-level security architecture abstraction decoupling application-level cryptography
 * from the transport and presentation layers.
 *
 * Responsibilities:
 * 1. Identity Management: Android Keystore hardware-backed EC P-256 identity keys.
 * 2. Authenticated Key Agreement: Ephemeral ECDH key agreement with HKDF-SHA256 session derivation.
 * 3. Peer Verification: Cryptographic Short Authentication String (SAS / Safety Number).
 * 4. Authenticated Encryption: AES-256-GCM AEAD encryption/decryption with unique nonces and digital signatures.
 * 5. Trust State & Identity Change Protection: Detecting key replacement and enforcing user verification.
 */
interface MessageSecurity {

    /**
     * Returns the local device's public identity key in X.509 format.
     */
    fun getLocalPublicKey(): ByteArray

    /**
     * Returns the local device's SHA-256 cryptographic identity fingerprint.
     */
    fun getLocalDeviceFingerprint(): String

    /**
     * Creates an authenticated handshake payload containing the identity public key,
     * an ephemeral key for forward secrecy, and an ECDSA signature over the payload.
     */
    fun createHandshakePayload(localDisplayName: String): HandshakePayload

    /**
     * Processes an incoming peer handshake payload:
     * 1. Validates digital signature and device identity hash.
     * 2. Detects unexpected identity key changes (key replacement attacks).
     * 3. Executes ECDH key agreement to derive an AES-256-GCM session key and Safety Number.
     * 4. Updates or persists peer record in Room.
     */
    suspend fun processHandshake(payload: HandshakePayload): HandshakeResult

    /**
     * Encrypts plaintext message into an [EncryptedMessagePayload] using AES-256-GCM with a fresh 12-byte nonce,
     * bound with associated data and an ECDSA digital signature.
     */
    suspend fun encryptMessage(plaintext: ByteArray, recipientPeerId: String, messageId: String): EncryptedMessagePayload

    /**
     * Authenticates and decrypts an [EncryptedMessagePayload] using the established peer session key.
     * Throws [SecurityException] if the signature or AEAD tag is invalid or tampered with.
     */
    suspend fun decryptMessage(encryptedPayload: EncryptedMessagePayload, senderPeerId: String, messageId: String): ByteArray

    /**
     * Returns the current cryptographic trust state for the given peer.
     */
    suspend fun getPeerTrustState(peerId: String): PeerTrustState

    /**
     * Marks a peer as explicitly VERIFIED by the user after out-of-band comparison of the Safety Number.
     */
    suspend fun verifyPeer(peerId: String)

    /**
     * Explicitly revokes trust for a peer, blocking encrypted communications.
     */
    suspend fun revokePeer(peerId: String)

    /**
     * Retrieves the active 6-digit verification code (Safety Number) for the peer session, or null if no session.
     */
    fun getSafetyNumber(peerId: String): String?

    /**
     * Returns true if an active authenticated session key exists for the peer.
     */
    fun hasEstablishedSession(peerId: String): Boolean

    /**
     * Cryptographically signs data using the non-exportable hardware-backed private key.
     */
    fun sign(data: ByteArray): ByteArray

    /**
     * Verifies digital signature against a peer's public key.
     */
    fun verify(peerPublicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}
