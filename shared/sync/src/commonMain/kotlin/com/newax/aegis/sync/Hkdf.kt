package com.newax.aegis.sync

/**
 * HKDF-SHA256 (RFC 5869) — extract-and-expand key derivation. Used for session
 * key derivation and blob-wrapping KEKs.
 */
object Hkdf {

    /**
     * @param ikm input keying material
     * @param salt optional; empty = HashLen zeros per RFC 5869
     * @param info optional context string
     * @param length output length in bytes
     */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(32 * 255)) { "HKDF output length must be 1..8160 bytes" }

        // Extract: PRK = HMAC-Hash(salt, IKM)
        val saltBytes = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = Hmac.sha256(saltBytes, ikm)

        // Expand: T(i) = HMAC(PRK, T(i-1) | info | i)
        val out = ArrayList<ByteArray>()
        var t = ByteArray(0)
        var counter = 1
        var produced = 0
        while (produced < length) {
            t = Hmac.sha256(prk, t + info + byteArrayOf(counter.toByte()))
            out.add(t)
            produced += t.size
            counter++
        }
        val joined = ByteArray(produced)
        var offset = 0
        for (block in out) {
            block.copyInto(joined, offset)
            offset += block.size
        }
        return joined.copyOf(length)
    }
}
