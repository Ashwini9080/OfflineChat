package com.offlinechat.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HkdfTest {

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "")
        val result = ByteArray(cleanHex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = cleanHex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    @Test
    fun `HKDF SHA-256 matches RFC 5869 Test Case 1`() {
        // RFC 5869 Test Case 1
        val ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val l = 42

        val expectedPrk = hexToBytes("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e0")
        val expectedOkm = hexToBytes("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")

        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals("PRK mismatch", expectedPrk, prk)

        val okm = Hkdf.expand(prk, info, l)
        assertArrayEquals("OKM mismatch", expectedOkm, okm)

        val directOkm = Hkdf.deriveKey(salt, ikm, info, l)
        assertArrayEquals("Direct deriveKey mismatch", expectedOkm, directOkm)
    }

    @Test
    fun `HKDF deriveKey with null or empty salt works reliably`() {
        val ikm = "offline-chat-shared-secret-input".toByteArray(Charsets.UTF_8)
        val info = "OfflineChat-Session-v1".toByteArray(Charsets.UTF_8)

        val key1 = Hkdf.deriveKey(null, ikm, info, 32)
        val key2 = Hkdf.deriveKey(ByteArray(0), ikm, info, 32)

        assertNotNull(key1)
        assertEquals(32, key1.size)
        assertArrayEquals("Null and empty salt should produce identical default zero-salt derivation", key1, key2)
    }

    @Test
    fun `different info strings produce cryptographically distinct keys`() {
        val ikm = "same-shared-secret-bytes".toByteArray(Charsets.UTF_8)
        val keyAes = Hkdf.deriveKey(null, ikm, "AES-Encryption".toByteArray(Charsets.UTF_8), 32)
        val keyMac = Hkdf.deriveKey(null, ikm, "HMAC-Authentication".toByteArray(Charsets.UTF_8), 32)

        assertEquals(32, keyAes.size)
        assertEquals(32, keyMac.size)
        // Verify key separation
        var isIdentical = true
        for (i in keyAes.indices) {
            if (keyAes[i] != keyMac[i]) {
                isIdentical = false
                break
            }
        }
        assertTrue("Different domain separation info should generate distinct keys", !isIdentical)
    }
}
