package com.offlinechat.security

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * Standard RFC 5869 HMAC-based Extract-and-Expand Key Derivation Function (HKDF)
 * implemented using HMAC-SHA256.
 *
 * Used for deriving cryptographically separate symmetric session keys and
 * verification codes from Diffie-Hellman shared secrets.
 */
object Hkdf {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32 // SHA-256 output length in bytes

    /**
     * HKDF-Extract(salt, ikm) -> PRK (Pseudo-Random Key)
     */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt == null || salt.isEmpty()) {
            ByteArray(HASH_LEN)
        } else {
            salt
        }

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(effectiveSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand(prk, info, length) -> OKM (Output Keying Material)
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        if (length > 255 * HASH_LEN) {
            throw IllegalArgumentException("Cannot expand to more than ${255 * HASH_LEN} bytes")
        }

        val n = ceil(length.toDouble() / HASH_LEN).toInt()
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        val resultStream = ByteArrayOutputStream()
        var t = ByteArray(0)

        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            resultStream.write(t)
        }

        val allBytes = resultStream.toByteArray()
        return allBytes.copyOfRange(0, length)
    }

    /**
     * Complete HKDF: Extract and Expand in a single call.
     */
    fun deriveKey(salt: ByteArray?, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        return expand(prk, info, length)
    }
}
