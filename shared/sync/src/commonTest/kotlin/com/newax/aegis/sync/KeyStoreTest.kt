package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyStoreTest {

    @Test
    fun identityAndPeersRoundTrip() {
        val store = InMemoryKeyStore()
        assertNull(store.loadIdentity())

        val stored = Identity.generate(FakeCrypto(31), "Windows")
        store.saveIdentity(stored)
        assertEquals("Windows", store.loadIdentity()!!.identity.displayName)
        assertEquals(stored.identity.deviceId, store.loadIdentity()!!.identity.deviceId)

        store.savePeer(PairedPeer("dev-aaa", "Mac", byteArrayOf(1), byteArrayOf(2), 5))
        store.savePeer(PairedPeer("dev-bbb", "iPhone", byteArrayOf(3), byteArrayOf(4), 6))
        assertEquals(2, store.pairedPeers().size)

        store.removePeer("dev-aaa")
        assertEquals(listOf("dev-bbb"), store.pairedPeers().map { it.deviceId })
    }
}
