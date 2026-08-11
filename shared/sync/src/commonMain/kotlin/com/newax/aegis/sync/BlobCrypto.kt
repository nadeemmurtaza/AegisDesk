package com.newax.aegis.sync

/**
 * Encrypted blob envelope (docs/SYNC_DESIGN.md §8): a random content key seals
 * the payload, and the content key is wrapped under a KEK derived from
 * ephemeral X25519 ECDH with the TARGET's long-term key — so only the target
 * can unwrap, and the blob is anonymous on the wire (no sender key material).
 * Used for command payloads and file chunks.
 */
object BlobCrypto {

    private const val INFO = "aegis-blob-v1"

    data class SealedBlob(
        val ephemeralPublicKey: ByteArray,
        val wrappedKeyNonce: ByteArray,
        val wrappedKey: ByteArray,
        val contentNonce: ByteArray,
        val ciphertext: ByteArray
    )

    /** Seal [plaintext] so only the holder of [targetEcdhPrivateKey] can open it. */
    fun seal(crypto: Crypto, plaintext: ByteArray, targetEcdhPublicKey: ByteArray): SealedBlob {
        val eph = crypto.newEcdhKeyPair()
        val kek = deriveKek(crypto, eph.privateKey, targetEcdhPublicKey)
        val contentKey = crypto.randomBytes(32)
        val contentNonce = crypto.randomBytes(12)
        val wrappedKeyNonce = crypto.randomBytes(12)
        val ciphertext = crypto.aeadSeal(contentKey, contentNonce, plaintext, aad = eph.publicKey)
        val wrappedKey = crypto.aeadSeal(kek, wrappedKeyNonce, contentKey, aad = eph.publicKey)
        return SealedBlob(eph.publicKey, wrappedKeyNonce, wrappedKey, contentNonce, ciphertext)
    }

    /** Open a blob with my long-term ECDH private key; null on any mismatch. */
    fun open(crypto: Crypto, blob: SealedBlob, myEcdhPrivateKey: ByteArray): ByteArray? {
        val kek = deriveKek(crypto, myEcdhPrivateKey, blob.ephemeralPublicKey)
        val contentKey = crypto.aeadOpen(kek, blob.wrappedKeyNonce, blob.wrappedKey, aad = blob.ephemeralPublicKey)
            ?: return null
        return crypto.aeadOpen(contentKey, blob.contentNonce, blob.ciphertext, aad = blob.ephemeralPublicKey)
    }

    private fun deriveKek(crypto: Crypto, myPrivateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val shared = crypto.ecdh(myPrivateKey, peerPublicKey)
        return crypto.hkdf(shared, ByteArray(0), INFO.encodeToByteArray(), 32)
    }
}
