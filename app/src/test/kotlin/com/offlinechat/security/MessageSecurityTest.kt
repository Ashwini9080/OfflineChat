package com.offlinechat.security

import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.repository.PeerRepository
import io.mockk.coEvery
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
import javax.crypto.AEADBadTagException

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
    fun `getLocalPublicKey returns valid non-empty byte array`() {
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
    fun `AES-256-GCM encryption and decryption roundtrip succeeds`() = runTest {
        val peerId = "peer_device_xyz"
        val peer = Peer(
            deviceId = peerId,
            displayName = "Remote Peer",
            publicKeyBytes = remoteKeyPair.public.encoded
        )
        coEvery { peerRepository.getPeerById(peerId) } returns peer

        val plaintext = "Hello from Phone A with AES-256-GCM AEAD encryption!".toByteArray(Charsets.UTF_8)
        val ciphertext = messageSecurity.encrypt(plaintext, peerId)

        // Verify ciphertext is larger than plaintext (12-byte IV + 16-byte tag)
        assertTrue(ciphertext.size >= plaintext.size + 28)

        // Decrypt with same peer session
        val decrypted = messageSecurity.decrypt(ciphertext, peerId)
        assertArrayEquals(plaintext, decrypted)
        assertEquals("Hello from Phone A with AES-256-GCM AEAD encryption!", String(decrypted, Charsets.UTF_8))
    }

    @Test(expected = Exception::class)
    fun `tampered ciphertext throws AEAD tag validation failure`() = runTest {
        val peerId = "peer_device_xyz"
        val peer = Peer(
            deviceId = peerId,
            displayName = "Remote Peer",
            publicKeyBytes = remoteKeyPair.public.encoded
        )
        coEvery { peerRepository.getPeerById(peerId) } returns peer

        val plaintext = "Secret data".toByteArray(Charsets.UTF_8)
        val ciphertext = messageSecurity.encrypt(plaintext, peerId)

        // Tamper with the ciphertext byte
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1].toInt() xor 0xFF).toByte()

        messageSecurity.decrypt(ciphertext, peerId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `payload smaller than minimum nonce and tag length throws IllegalArgumentException`() = runTest {
        val tooShortPayload = ByteArray(15) // Less than 28 bytes
        messageSecurity.decrypt(tooShortPayload, "any_peer")
    }
}
