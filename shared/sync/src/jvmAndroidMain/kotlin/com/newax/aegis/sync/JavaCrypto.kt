package com.newax.aegis.sync

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The production crypto implementation — JDK 17 (and Android 12+ conscrypt)
 * Ed25519 / X25519 / AES-256-GCM via JCA, with zero external dependencies.
 * sha256/hmac/hkdf delegate to the pure-Kotlin implementations so test and
 * production behavior are identical.
 *
 * Runtime availability is guarded: on platforms without Ed25519/X25519
 * providers (Android < 12) the constructor throws a clear [IllegalStateException]
 * — a named failure (R9), never a silent fallback to weaker crypto.
 */
class JavaCrypto : Crypto {

    private val random = SecureRandom()

    init {
        // Fail fast at construction if the platform lacks the algorithms.
        try {
            KeyPairGenerator.getInstance("Ed25519")
            KeyPairGenerator.getInstance("X25519")
        } catch (e: Exception) {
            throw IllegalStateException(
                "This platform lacks Ed25519/X25519 JCA providers (requires JDK 15+ / Android 12+); " +
                    "refusing to fall back to weaker crypto.", e
            )
        }
    }

    override fun newSignKeyPair(): KeyPair {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        return KeyPair(kp.private.encoded, kp.public.encoded)
    }

    override fun newEcdhKeyPair(): KeyPair {
        val kp = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        return KeyPair(kp.private.encoded, kp.public.encoded)
    }

    override fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val s = Signature.getInstance("Ed25519")
        s.initSign(key)
        s.update(message)
        return s.sign()
    }

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKey))
            val s = Signature.getInstance("Ed25519")
            s.initVerify(key)
            s.update(message)
            s.verify(signature)
        } catch (e: Exception) {
            false
        }

    override fun ecdh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val pub = kf.generatePublic(X509EncodedKeySpec(publicKey))
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    override fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray? =
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            if (aad != null) cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

actual fun platformCrypto(): Crypto = JavaCrypto()
