package com.newax.aegis.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * OsKeyStore round-trips. On Linux CI this exercises the dev plaintext branch;
 * on a Windows or macOS machine it exercises the real DPAPI / Keychain paths
 * (the two branches that cannot be verified on this repo's Linux runners).
 */
class OsKeyStoreTest {

    @Test
    fun identityRoundTripsAcrossInstances() {
        val crypto = platformCrypto()
        val dir = Files.createTempDirectory("aegis-oskeys").toFile()
        val identity = Identity.generate(crypto, "OsKeyStoreTest")
        OsKeyStore(dir).saveIdentity(identity)

        // A fresh store instance over the same directory (simulates restart).
        val reloaded = OsKeyStore(dir).loadIdentity()
        assertNotNull(reloaded, "identity must survive a reload on ${System.getProperty("os.name")}")
        assertEquals(identity.identity.deviceId, reloaded.identity.deviceId)
        assertEquals("OsKeyStoreTest", reloaded.identity.displayName)
    }

    @Test
    fun legacyDevIdentityIsReadUntilNextSave() {
        val crypto = platformCrypto()
        val dir = Files.createTempDirectory("aegis-oskeys").toFile()
        val legacy = Identity.generate(crypto, "pre-vault")
        // The pre-S5 dev store writes identity.txt; OsKeyStore must keep the id.
        FileKeyStore(dir).saveIdentity(legacy)

        val loaded = OsKeyStore(dir).loadIdentity()
        assertNotNull(loaded, "legacy identity.txt must be honored")
        assertEquals(legacy.identity.deviceId, loaded.identity.deviceId)
    }

    @Test
    fun peersAreSharedWithFileKeyStore() {
        val dir = Files.createTempDirectory("aegis-oskeys").toFile()
        FileKeyStore(dir).savePeer(PairedPeer("dev-aaa", "Peer", byteArrayOf(1), byteArrayOf(2), 5))

        val store = OsKeyStore(dir)
        assertEquals(listOf("dev-aaa"), store.pairedPeers().map { it.deviceId })

        store.removePeer("dev-aaa")
        assertEquals(0, OsKeyStore(dir).pairedPeers().size)
    }
}
