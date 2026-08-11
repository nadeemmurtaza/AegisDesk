package com.newax.aegis.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileKeyStoreTest {

    private fun store(): FileKeyStore =
        FileKeyStore(Files.createTempDirectory("aegis-keys").toFile())

    @Test
    fun identityAndPeersRoundTripAcrossInstances() {
        val crypto = platformCrypto()
        val dir = Files.createTempDirectory("aegis-keys").toFile()
        FileKeyStore(dir).apply {
            val identity = Identity.generate(crypto, "Windows")
            saveIdentity(identity)
            savePeer(PairedPeer("dev-aaa", "Mac", byteArrayOf(1), byteArrayOf(2), 5))
            savePeer(PairedPeer("dev-bbb", "iPhone", byteArrayOf(3), byteArrayOf(4), 6))
        }

        // A fresh store instance over the same directory (simulates restart).
        val reloaded = FileKeyStore(dir)
        val identity = reloaded.loadIdentity()!!
        assertEquals("Windows", identity.identity.displayName)
        assertEquals(2, reloaded.pairedPeers().size)
        assertEquals(setOf("dev-aaa", "dev-bbb"), reloaded.pairedPeers().map { it.deviceId }.toSet())

        reloaded.removePeer("dev-aaa")
        assertEquals(listOf("dev-bbb"), FileKeyStore(dir).pairedPeers().map { it.deviceId })
    }

    @Test
    fun missingOrCorruptIdentityIsNull() {
        val fresh = store()
        assertNull(fresh.loadIdentity())

        val dir = Files.createTempDirectory("aegis-keys").toFile()
        val f = java.io.File(dir, "identity.txt")
        f.writeText("only-one-field")
        assertNull(FileKeyStore(dir).loadIdentity())

        f.writeText("a|zz|00|00|00|00|00") // bad hex in field 2
        assertNull(FileKeyStore(dir).loadIdentity())
    }

    @Test
    fun escapingSurvivesRoundTrip() {
        val crypto = platformCrypto()
        val dir = Files.createTempDirectory("aegis-keys").toFile()
        FileKeyStore(dir).savePeer(PairedPeer("dev-1", "Dev|A\\B", byteArrayOf(9), byteArrayOf(8), 1))
        val peers = FileKeyStore(dir).pairedPeers()
        assertTrue(peers.size == 1)
        assertEquals("Dev|A\\B", peers[0].displayName)
    }

    @Test
    fun privateKeysPersistVerbatim() {
        val crypto = platformCrypto()
        val dir = Files.createTempDirectory("aegis-keys").toFile()
        val identity = Identity.generate(crypto, "Windows")
        FileKeyStore(dir).saveIdentity(identity)
        val loaded = FileKeyStore(dir).loadIdentity()!!
        assertContentEquals(identity.signPrivateKey, loaded.signPrivateKey)
        assertContentEquals(identity.ecdhPrivateKey, loaded.ecdhPrivateKey)
        // The stored identity still signs and verifies.
        val sig = crypto.sign(loaded.signPrivateKey, "x".encodeToByteArray())
        assertTrue(crypto.verify(loaded.identity.signPublicKey, "x".encodeToByteArray(), sig))
    }
}
