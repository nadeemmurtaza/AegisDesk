package com.newax.aegis.sync

/**
 * Platform-free crypto primitives (docs/SYNC_DESIGN.md §8). The protocol layer
 * (Pairing, SessionCrypto, BlobCrypto) is written purely against this
 * interface; the JVM/Android implementation is `JavaCrypto` (JDK Ed25519 /
 * X25519 / AES-GCM — no external deps), and tests use a deterministic fake.
 *
 * Hashing primitives default to the pure-Kotlin implementations so test and
 * production behavior are identical for sha256/hmac/hkdf.
 */
interface Crypto {

    /** Ed25519 signing keypair (private = PKCS#8, public = X.509 SubjectPublicKeyInfo). */
    fun newSignKeyPair(): KeyPair

    /** X25519 key-agreement keypair (same encodings). */
    fun newEcdhKeyPair(): KeyPair

    /** Ed25519 signature over [message]. */
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray

    /** Constant-time-ish verification; false on any invalid input, never throws. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    /** X25519 shared secret (32 bytes): ecdh(a.priv, b.pub) == ecdh(b.priv, a.pub). */
    fun ecdh(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    fun sha256(data: ByteArray): ByteArray = Sha256.digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = Hmac.sha256(key, data)

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        Hkdf.derive(ikm, salt, info, length)

    /**
     * AEAD seal: ciphertext || auth tag (GCM in production). Null [aad] = no
     * associated data.
     */
    fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray

    /** AEAD open; null on authentication failure (tamper, wrong key/nonce). */
    fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray?

    /** Cryptographically secure random bytes. */
    fun randomBytes(size: Int): ByteArray
}

/** A raw keypair — [privateKey] and [publicKey] in platform-encoded form. */
data class KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
)

/**
 * The platform seams (actuals in jvmAndroidMain: JavaCrypto + FileKeyStore;
 * iOS Keychain-backed stores arrive with Phase 0 / the wiring slice).
 * Callers receive the platform default; tests construct their own.
 */
expect fun platformCrypto(): Crypto

expect fun platformKeyStore(): KeyStore
