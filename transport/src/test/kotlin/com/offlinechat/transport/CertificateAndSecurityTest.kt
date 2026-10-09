package com.offlinechat.transport

import com.offlinechat.core.result.AppResult
import com.offlinechat.security.SessionCrypto
import com.offlinechat.security.model.DeviceCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class CertificateAndSecurityTest {

    @Test
    fun testEcdhKeyExchangeAndSessionKeyAgreement() {
        val crypto = SessionCrypto()

        // Device A ephemeral keypair
        val keyPairResA = crypto.generateEphemeralDhKeyPair()
        assertTrue(keyPairResA is AppResult.Success)
        val keyPairA = (keyPairResA as AppResult.Success).data

        // Device B ephemeral keypair
        val keyPairResB = crypto.generateEphemeralDhKeyPair()
        assertTrue(keyPairResB is AppResult.Success)
        val keyPairB = (keyPairResB as AppResult.Success).data

        // Device A computes shared secret using B's public key
        val secretResA = crypto.computeSharedSecret(keyPairA.private, keyPairB.public.encoded)
        assertTrue(secretResA is AppResult.Success)
        val secretA = (secretResA as AppResult.Success).data

        // Device B computes shared secret using A's public key
        val secretResB = crypto.computeSharedSecret(keyPairB.private, keyPairA.public.encoded)
        assertTrue(secretResB is AppResult.Success)
        val secretB = (secretResB as AppResult.Success).data

        // Shared secrets must match
        assertArrayEquals(secretA, secretB)

        // Deriving session keys on both sides
        val resultA = crypto.deriveSessionKey(secretA, initiatorId = "devA", responderId = "devB")
        assertTrue(resultA is AppResult.Success)
        val sessionKeyA = (resultA as AppResult.Success).data

        val resultB = crypto.deriveSessionKey(secretB, initiatorId = "devA", responderId = "devB")
        assertTrue(resultB is AppResult.Success)
        val sessionKeyB = (resultB as AppResult.Success).data

        // Both session keys MUST match
        assertArrayEquals(sessionKeyA.encoded, sessionKeyB.encoded)
        assertEquals(32, sessionKeyA.encoded.size) // AES-256
    }

    @Test
    fun testCertificateSignableBytesCoverage() {
        val cert1 = DeviceCertificate(
            deviceId = "device_test_1",
            displayName = "Device One",
            publicSigningKeyBase64 = "AAAA",
            publicDhKeyBase64 = "BBBB",
            issuedAt = 12345678L,
            signature = "CCCC"
        )

        val cert2 = cert1.copy(displayName = "Device Modified")

        // Any modification in signable fields must produce different canonical signable bytes
        assertFalse(cert1.signableBytes().contentEquals(cert2.signableBytes()))
    }
}
