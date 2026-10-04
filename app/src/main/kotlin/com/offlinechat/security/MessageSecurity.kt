package com.offlinechat.security

/**
 * Security abstraction decoupling application-level cryptography from transport layers.
 *
 * Requirements:
 * 1. Key Management: Android Keystore hardware-backed keys, never exposed in plaintext.
 * 2. Authenticated Encryption: AES-256-GCM AEAD payload encryption & decryption.
 * 3. Digital Signatures: ECDSA P-256 signing and verification.
 * 4. Zero Secrets in Source: Never hard-code keys, never log keys or message content.
 */
interface MessageSecurity {

    /**
     * Retrieves the local device's public key (X.509 encoded bytes) from Android Keystore.
     */
    fun getLocalPublicKey(): ByteArray

    /**
     * Cryptographically signs data using the non-exportable hardware-backed private key.
     */
    fun sign(data: ByteArray): ByteArray

    /**
     * Verifies digital signature against a peer's public key.
     */
    fun verify(peerPublicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean

    /**
     * Encrypts plaintext payload using AES-256-GCM with a 12-byte random nonce
     * and 128-bit authentication tag.
     *
     * Output format: [12-byte Nonce] + [Ciphertext + Tag]
     */
    suspend fun encrypt(plaintext: ByteArray, recipientPeerId: String): ByteArray

    /**
     * Decrypts AES-256-GCM ciphertext payload after validating authentication tag.
     *
     * Input format: [12-byte Nonce] + [Ciphertext + Tag]
     */
    suspend fun decrypt(ciphertext: ByteArray, senderPeerId: String): ByteArray

    /**
     * Returns true if an authenticated key establishment has occurred for the given peer.
     */
    fun hasEstablishedSession(peerId: String): Boolean
}
