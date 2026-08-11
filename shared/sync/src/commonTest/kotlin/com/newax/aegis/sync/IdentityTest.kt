package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityTest {

    @Test
    fun deviceIdIsStableAndDerivedFromFingerprint() {
        val stored = Identity.generate(FakeCrypto(1), "Windows")
        val id = stored.identity
        assertTrue(id.deviceId.startsWith("dev-"))
        assertEquals("dev-" + id.fingerprintHex.take(10), id.deviceId)
        assertEquals(8, id.shortFingerprint.length)
        assertEquals(64, id.fingerprintHex.length) // sha256 hex
        assertTrue(id.deviceId == id.deviceId)      // stable across reads
    }

    @Test
    fun identitiesAreDistinct() {
        val a = Identity.generate(FakeCrypto(1), "A").identity
        val b = Identity.generate(FakeCrypto(2), "B").identity
        assertNotEquals(a.deviceId, b.deviceId)
        assertNotEquals(a.fingerprintHex, b.fingerprintHex)
    }

    @Test
    fun equalityIsKeyBasedNotReferenceBased() {
        val stored = Identity.generate(FakeCrypto(7), "Mac")
        val copy = StoredIdentity(
            identity = DeviceIdentity("Mac", stored.identity.signPublicKey.copyOf(), stored.identity.ecdhPublicKey.copyOf()),
            signPrivateKey = stored.signPrivateKey.copyOf(),
            ecdhPrivateKey = stored.ecdhPrivateKey.copyOf()
        )
        assertEquals(stored.identity, copy.identity)
    }
}
