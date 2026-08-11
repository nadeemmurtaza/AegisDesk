package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals

class HashTest {

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    // FIPS 180-4 / NIST vectors
    @Test
    fun sha256KnownVectors() {
        assertContentEquals(hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), Sha256.digest(ByteArray(0)))
        assertContentEquals(hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), Sha256.digest("abc".encodeToByteArray()))
        assertContentEquals(
            hex("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"),
            Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())
        )
        // multi-block + length-carry: one million 'a'
        assertContentEquals(
            hex("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"),
            Sha256.digest(ByteArray(1_000_000) { 'a'.code.toByte() })
        )
    }

    // RFC 4231 test case 1: key = 0x0b × 20, data = "Hi There"
    @Test
    fun hmacSha256Rfc4231Case1() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".encodeToByteArray()
        assertContentEquals(
            hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            Hmac.sha256(key, data)
        )
    }

    // RFC 5869 appendix A.1
    @Test
    fun hkdfRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() } // 0x00..0x0c
        val info = ByteArray(10) { (0xf0 + it).toByte() } // 0xf0..0xf9
        assertContentEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            Hkdf.derive(ikm, salt, info, 42)
        )
    }
}
