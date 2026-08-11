package com.newax.aegis.sync

/** HMAC-SHA256 (RFC 2104) over the pure-Kotlin [Sha256]. */
object Hmac {

    private const val BLOCK_SIZE = 64 // SHA-256 block size in bytes

    fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        val k = if (key.size > BLOCK_SIZE) Sha256.digest(key) else key
        val block = ByteArray(BLOCK_SIZE)
        k.copyInto(block)
        val iPad = ByteArray(BLOCK_SIZE) { (block[it].toInt() xor 0x36).toByte() }
        val oPad = ByteArray(BLOCK_SIZE) { (block[it].toInt() xor 0x5c).toByte() }
        return Sha256.digest(oPad + Sha256.digest(iPad + data))
    }
}
