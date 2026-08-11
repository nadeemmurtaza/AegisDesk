package com.newax.aegis.sync

/**
 * Deterministic crypto double for protocol tests. sha256/hmac/hkdf are the
 * REAL pure-Kotlin implementations; only the asymmetric/authenticated pieces
 * are faked — but with real semantics:
 * - sign/verify: HMAC keyed by the key material (deterministic, tamper-detectable)
 * - ecdh: symmetric — keypairs have public == private bytes, shared secret =
 *   sha256(sorted pair) so ecdh(a,b) == ecdh(b,a)
 * - aead: XOR keystream + HMAC tag → open fails on tamper/wrong key/nonce
 */
class FakeCrypto(seed: Long = 42) : Crypto {

    private var state = seed

    private fun nextLong(): Long {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = x
        return x
    }

    override fun randomBytes(size: Int): ByteArray = ByteArray(size) { nextLong().toByte() }

    override fun newSignKeyPair(): KeyPair {
        val priv = randomBytes(32)
        return KeyPair(priv, priv.copyOf())
    }

    override fun newEcdhKeyPair(): KeyPair = newSignKeyPair()

    override fun sign(privateKey: ByteArray, message: ByteArray): ByteArray =
        hmacSha256(privateKey, message)

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        signature.contentEquals(hmacSha256(publicKey, message))

    override fun ecdh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val a = privateKey
        val b = publicKey
        return if (lessThan(a, b)) sha256(a + b) else sha256(b + a)
    }

    override fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val ks = keystream(key, nonce, aad, plaintext.size)
        val ct = ByteArray(plaintext.size) { (plaintext[it].toInt() xor ks[it].toInt()).toByte() }
        val tag = hmacSha256(key, nonce + (aad ?: ByteArray(0)) + ct)
        return tag + ct
    }

    override fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray? {
        if (ciphertext.size < 32) return null
        val tag = ciphertext.copyOfRange(0, 32)
        val ct = ciphertext.copyOfRange(32, ciphertext.size)
        if (!tag.contentEquals(hmacSha256(key, nonce + (aad ?: ByteArray(0)) + ct))) return null
        val ks = keystream(key, nonce, aad, ct.size)
        return ByteArray(ct.size) { (ct[it].toInt() xor ks[it].toInt()).toByte() }
    }

    private fun keystream(key: ByteArray, nonce: ByteArray, aad: ByteArray?, length: Int): ByteArray {
        val out = ByteArray(length)
        var block = 0
        var off = 0
        while (off < length) {
            val b = sha256(key + nonce + (aad ?: ByteArray(0)) + byteArrayOf(block.toByte()))
            val n = minOf(b.size, length - off)
            b.copyInto(out, off, 0, n)
            off += n
            block++
        }
        return out
    }

    private fun lessThan(a: ByteArray, b: ByteArray): Boolean {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a[i].toInt() and 0xFF
            val y = b[i].toInt() and 0xFF
            if (x != y) return x < y
        }
        return a.size < b.size
    }
}
