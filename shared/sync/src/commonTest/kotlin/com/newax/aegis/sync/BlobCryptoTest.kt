package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class BlobCryptoTest {

    private val crypto = FakeCrypto()

    @Test
    fun onlyTargetCanOpen() {
        val alice = Identity.generate(FakeCrypto(21), "A")
        val bob = Identity.generate(FakeCrypto(22), "B")

        val blob = BlobCrypto.seal(crypto, "secret message".encodeToByteArray(), bob.identity.ecdhPublicKey)

        assertContentEquals("secret message".encodeToByteArray(), BlobCrypto.open(crypto, blob, bob.ecdhPrivateKey)!!)
        assertNull(BlobCrypto.open(crypto, blob, alice.ecdhPrivateKey))
    }

    @Test
    fun tamperedCiphertextFailsOpen() {
        val bob = Identity.generate(FakeCrypto(22), "B")
        val blob = BlobCrypto.seal(crypto, "secret".encodeToByteArray(), bob.identity.ecdhPublicKey)

        val tampered = blob.copy(ciphertext = blob.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertNull(BlobCrypto.open(crypto, tampered, bob.ecdhPrivateKey))

        val tamperedKey = blob.copy(wrappedKey = blob.wrappedKey.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertNull(BlobCrypto.open(crypto, tamperedKey, bob.ecdhPrivateKey))
    }

    @Test
    fun emptyPayloadRoundTrips() {
        val bob = Identity.generate(FakeCrypto(22), "B")
        val blob = BlobCrypto.seal(crypto, ByteArray(0), bob.identity.ecdhPublicKey)
        assertContentEquals(ByteArray(0), BlobCrypto.open(crypto, blob, bob.ecdhPrivateKey)!!)
    }
}
