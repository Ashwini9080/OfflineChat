package com.offlinechat.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystoreManager @Inject constructor() {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS_IDENTITY = "offline_chat_identity_key"
        private const val SIGNING_ALGORITHM = "SHA256withECDSA"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /**
     * Gets existing EC keypair or generates a new hardware-backed keypair in Android Keystore.
     * The private key is non-exportable and never leaves secure hardware in plaintext.
     */
    fun getOrCreateIdentityKeyPair(): KeyPair {
        if (keyStore.containsAlias(KEY_ALIAS_IDENTITY)) {
            val entry = keyStore.getEntry(KEY_ALIAS_IDENTITY, null) as KeyStore.PrivateKeyEntry
            return KeyPair(entry.certificate.publicKey, entry.privateKey)
        }

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS_IDENTITY,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        kpg.initialize(parameterSpec)
        return kpg.generateKeyPair()
    }

    fun getPublicKey(): PublicKey {
        return getOrCreateIdentityKeyPair().public
    }

    /**
     * Cryptographically signs data with the Keystore-backed private key.
     */
    fun sign(data: ByteArray): ByteArray {
        val privateKey = (keyStore.getEntry(KEY_ALIAS_IDENTITY, null) as? KeyStore.PrivateKeyEntry)?.privateKey
            ?: getOrCreateIdentityKeyPair().private

        val signature = Signature.getInstance(SIGNING_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    /**
     * Verifies signature against the peer's public key.
     */
    fun verify(peerPublicKey: PublicKey, data: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            val signature = Signature.getInstance(SIGNING_ALGORITHM)
            signature.initVerify(peerPublicKey)
            signature.update(data)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }
}
