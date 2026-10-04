package com.offlinechat.security

import com.offlinechat.domain.model.HandshakePayload
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerTrustState
import com.offlinechat.domain.repository.PeerRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class MessageSecurityTest {

    private lateinit var keystoreManager: KeystoreManager
    private lateinit var peerRepository: PeerRepository
    private lateinit var sessionCrypto: SessionCrypto
    private lateinit var messageSecurity: MessageSecurityImpl

    private val kpg = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }
    private val localKeyPair = kpg.generateKeyPair()
    private val remoteKeyPair = kpg.generateKeyPair()
    private val attackerKeyPair = kpg.generateKeyPair()

    @Before
    fun setUp() {
        keystoreManager = mockk(relaxed = true)
        peerRepository = mockk(relaxed = true)
        sessionCrypto = SessionCrypto()

        every { keystoreManager.getOrCreateIdentityKeyPair() } returns localKeyPair
        every { keystoreManager.getPublicKey() } returns localKeyPair.public
        every { keystoreManager.sign(any()) } answers {
            val data = firstArg<ByteArray>()
            val sig = java.security.Signature.getInstance("SHA256withECDSA")
            sig.initSign(localKeyPair.private)
            sig.update(data)
            sig.sign()
        }
        every { keystoreManager.verify(any(), any(), any()) } answers {
            val pubKey = firstArg<java.security.PublicKey>()
            val data = secondArg<ByteArray>()
            val signature = thirdArg<ByteArray>()
            try {
                val sig = java.security.Signature.getInstance("SHA256withECDSA")
                sig.initVerify(pubKey)
                sig.update(data)
                sig.verify(signature)
            } catch (e: Exception) {
                false
            }
        }

        messageSecurity = MessageSecurityImpl(
            keystoreManager = keystoreManager,
            sessionCrypto = sessionCrypto,
            peerRepository = peerRepository
        )
    }

    @Test
    fun `getLocalPublicKey returns valid non-empty byte array matching Keystore identity`() {
        val pubKey = messageSecurity.getLocalPublicKey()
        assertNotNull(pubKey)
        assertTrue(pubKey.isNotEmpty())
        assertArrayEquals(localKeyPair.public.encoded, pubKey)
    }

    @Test
    fun `digital signature verification succeeds for authentic data and fails for tampered data`() {
        val data = "Offline P2P Secret Payload".toByteArray(Charsets.UTF_8)
        val signature = messageSecurity.sign(data)

        assertTrue(messageSecurity.verify(localKeyPair.public.encoded, data, signature))

        val tamperedData = "Tampered Payload".toByteArray(Charsets.UTF_8)
        assertFalse(messageSecurity.verify(localKeyPair.public.encoded, tamperedData, signature))
    }

    @Test
    fun `initiateHandshake generates valid signed payload with ephemeral key`() {
        val peerId = "remote_peer_123"
        val handshake = messageSecurity.initiateHandshake(peerId)

        assertNotNull(handshake)
        assertEquals(1, handshake.protocolVersion)
        assertEquals("HANDSHAKE_INIT", handshake.type)
        assertNotNull(handshake.ephemeralPublicKeyBase64)
        assertNotNull(handshake.identityPublicKeyBase64)
        assertNotNull(handshake.signatureBase64)
        assertNotNull(handshake.identityFingerprint)
    }

    @Test
    fun `processHandshake establishes session and derives identical symmetric Safety Number`() = runTest {
        val peerId = "remote_peer_456"
        val peer = Peer(
            deviceId = peerId,
            displayName = "Remote Device",
            publicKeyBytes = remoteKeyPair.public.encoded,
            trustState = PeerTrustState.CONNECTED
        )
        coEvery { peerRepository.getPeerById(peerId) } returns peer

        // Remote peer initiates handshake
        val remoteEphemeralKeyPair = kpg.generateKeyPair()
        val dataToSign = remoteEphemeralKeyPair.public.encoded
        val sig = java.security.Signature.getInstance("SHA256withECDSA").apply {
            initSign(remoteKeyPair.private)
            update(dataToSign)
        }
        val remoteSig = sig.sign()

        val incomingHandshake = HandshakePayload(
            protocolVersion = 1,
            type = "HANDSHAKE_INIT",
            identityPublicKeyBase64 = Base64.getEncoder().encodeToString(remoteKeyPair.public.encoded),
            ephemeralPublicKeyBase64 = Base64.getEncoder().encodeToString(remoteEphemeralKeyPair.public.encoded),
            signatureBase64 = Base64.getEncoder().encodeToString(remoteSig),
            identityFingerprint = IdentityManager.fingerprint(remoteKeyPair.public.encoded)
        )

        val responseHandshake = messageSecurity.processHandshake(incomingHandshake, peerId)
        assertNotNull(responseHandshake)
        assertEquals("HANDSHAKE_RESP", responseHandshake.type)

        // Verify Safety Number exists and is formatted as "### ###"
        val safetyNumber = messageSecurity.getSafetyNumber(peerId)
        assertNotNull(safetyNumber)
        assertTrue(safetyNumber!!.matches(Regex("\\d{3} \\d{3}")))

        coVerify { peerRepository.updateTrustState(peerId, PeerTrustState.VERIFICATION_REQUIRED, safetyNumber) }
    }

    @Test(expected = SecurityException::class)
    fun `processHandshake detects identity change and sets trust state to REVOKED`() = runTest {
        val peerId = "known_trusted_peer"
        // Peer was previously stored with remoteKeyPair
        val existingPeer = Peer(
            deviceId = peerId,
            displayName = "Rahul Phone",
            publicKeyBytes = remoteKeyPair.public.encoded,
            trustState = PeerTrustState.VERIFIED
        )
        coEvery { peerRepository.getPeerById(peerId) } returns existingPeer

        // Attacker attempts key replacement using attackerKeyPair
        val attackerEphemeral = kpg.generateKeyPair()
        val sig = java.security.Signature.getInstance("SHA256withECDSA").apply {
            initSign(attackerKeyPair.private)
            update(attackerEphemeral.public.encoded)
        }
        val attackerSig = sig.sign()

        val attackHandshake = HandshakePayload(
            protocolVersion = 1,
            type = "HANDSHAKE_INIT",
            identityPublicKeyBase64 = Base64.getEncoder().encodeToString(attackerKeyPair.public.encoded),
            ephemeralPublicKeyBase64 = Base64.getEncoder().encodeToString(attackerEphemeral.public.encoded),
            signatureBase64 = Base64.getEncoder().encodeToString(attackerSig),
            identityFingerprint = IdentityManager.fingerprint(attackerKeyPair.public.encoded)
        )

        try {
            messageSecurity.processHandshake(attackHandshake, peerId)
        } finally {
            // Verify that identity mismatch immediately transitions state to REVOKED
            coVerify { peerRepository.updateTrustState(peerId, PeerTrustState.REVOKED, null) }
        }
    }

    @Test
    fun `AES-256-GCM message encryption and decryption roundtrip succeeds with unique nonces`() = runTest {
        val peerId = "peer_device_xyz"
        val peer = Peer(
            deviceId = peerId,
            displayName = "Remote Peer",
            publicKeyBytes = remoteKeyPair.public.encoded,
            trustState = PeerTrustState.VERIFIED
        )
        coEvery { peerRepository.getPeerById(peerId) } returns peer

        // Set up active session
        val remoteEphemeralKeyPair = kpg.generateKeyPair()
        val sig = java.security.Signature.getInstance("SHA256withECDSA").apply {
            initSign(remoteKeyPair.private)
            update(remoteEphemeralKeyPair.public.encoded)
        }
        val handshake = HandshakePayload(
            protocolVersion = 1,
            type = "HANDSHAKE_INIT",
            identityPublicKeyBase64 = Base64.getEncoder().encodeToString(remoteKeyPair.public.encoded),
            ephemeralPublicKeyBase64 = Base64.getEncoder().encodeToString(remoteEphemeralKeyPair.public.encoded),
            signatureBase64 = Base64.getEncoder().encodeToString(sig.sign()),
            identityFingerprint = IdentityManager.fingerprint(remoteKeyPair.public.encoded)
        )
        messageSecurity.processHandshake(handshake, peerId)

        val plaintext1 = "Message number 1 over Bluetooth E2EE".toByteArray(Charsets.UTF_8)
        val encrypted1 = messageSecurity.encryptMessage(plaintext1, peerId, "msg-001")

        val plaintext2 = "Message number 2 over Bluetooth E2EE".toByteArray(Charsets.UTF_8)
        val encrypted2 = messageSecurity.encryptMessage(plaintext2, peerId, "msg-002")

        // 1. Unique nonces per encryption operation (prevents nonce reuse)
        assertFalse(encrypted1.nonceBase64 == encrypted2.nonceBase64)

        // 2. Both nonces decode to exactly 12 bytes
        val nonce1 = Base64.getDecoder().decode(encrypted1.nonceBase64)
        val nonce2 = Base64.getDecoder().decode(encrypted2.nonceBase64)
        assertEquals(12, nonce1.size)
        assertEquals(12, nonce2.size)

        // 3. Successful decryption
        val decrypted1 = messageSecurity.decryptMessage(encrypted1, peerId, "msg-001")
        assertArrayEquals(plaintext1, decrypted1)
        assertEquals("Message number 1 over Bluetooth E2EE", String(decrypted1, Charsets.UTF_8))

        val decrypted2 = messageSecurity.decryptMessage(encrypted2, peerId, "msg-002")
        assertArrayEquals(plaintext2, decrypted2)
        assertEquals("Message number 2 over Bluetooth E2EE", String(decrypted2, Charsets.UTF_8))
    }

    @Test(expected = SecurityException::class)
    fun `tampered ciphertext in encrypted message throws SecurityException`() = runTest {
        val peerId = "peer_device_xyz"
        val peer = Peer(
            deviceId = peerId,
            displayName = "Remote Peer",
            publicKeyBytes = remoteKeyPair.public.encoded,
            trustState = PeerTrustState.VERIFIED
        )
        coEvery { peerRepository.getPeerById(peerId) } returns peer

        val remoteEphemeralKeyPair = kpg.generateKeyPair()
        val sig = java.security.Signature.getInstance("SHA256withECDSA").apply {
            initSign(remoteKeyPair.private)
            update(remoteEphemeralKeyPair.public.encoded)
        }
        val handshake = HandshakePayload(
            protocolVersion = 1,
            type = "HANDSHAKE_INIT",
            identityPublicKeyBase64 = Base64.getEncoder().encodeToString(remoteKeyPair.public.encoded),
            ephemeralPublicKeyBase64 = Base64.getEncoder().encodeToString(remoteEphemeralKeyPair.public.encoded),
            signatureBase64 = Base64.getEncoder().encodeToString(sig.sign()),
            identityFingerprint = IdentityManager.fingerprint(remoteKeyPair.public.encoded)
        )
        messageSecurity.processHandshake(handshake, peerId)

        val plaintext = "Top secret data".toByteArray(Charsets.UTF_8)
        val encrypted = messageSecurity.encryptMessage(plaintext, peerId, "msg-003")

        // Tamper with ciphertext
        val cipherBytes = Base64.getDecoder().decode(encrypted.ciphertextBase64)
        cipherBytes[0] = (cipherBytes[0].toInt() xor 0xFF).toByte()
        val tamperedEncrypted = encrypted.copy(ciphertextBase64 = Base64.getEncoder().encodeToString(cipherBytes))

        messageSecurity.decryptMessage(tamperedEncrypted, peerId, "msg-003")
    }

    @Test(expected = SecurityException::class)
    fun `tampered messageId in Associated Data fails AEAD authentication`() = runTest {
        val peerId = "peer_device_xyz"
        val peer = Peer(
            deviceId = peerId,
            displayName = "Remote Peer",
            publicKeyBytes = remoteKeyPair.public.encoded,
            trustState = PeerTrustState.VERIFIED
        )
        coEvery { peerRepository.getPeerById(peerId) } returns peer

        val remoteEphemeralKeyPair = kpg.generateKeyPair()
        val sig = java.security.Signature.getInstance("SHA256withECDSA").apply {
            initSign(remoteKeyPair.private)
            update(remoteEphemeralKeyPair.public.encoded)
        }
        val handshake = HandshakePayload(
            protocolVersion = 1,
            type = "HANDSHAKE_INIT",
            identityPublicKeyBase64 = Base64.getEncoder().encodeToString(remoteKeyPair.public.encoded),
            ephemeralPublicKeyBase64 = Base64.getEncoder().encodeToString(remoteEphemeralKeyPair.public.encoded),
            signatureBase64 = Base64.getEncoder().encodeToString(sig.sign()),
            identityFingerprint = IdentityManager.fingerprint(remoteKeyPair.public.encoded)
        )
        messageSecurity.processHandshake(handshake, peerId)

        val plaintext = "Top secret data".toByteArray(Charsets.UTF_8)
        val encrypted = messageSecurity.encryptMessage(plaintext, peerId, "msg-004")

        // Attacker changes the message ID in transit; AEAD AAD tag check should immediately reject
        messageSecurity.decryptMessage(encrypted, peerId, "msg-999-tampered-id")
    }
}
