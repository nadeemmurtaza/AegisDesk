package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The proximity advertisement payload codec: round-trips device identity
 * through the 27-byte BLE budget, truncates display names to fit, and rejects
 * malformed bytes (foreign advertisements are data, never trusted).
 */
class ProximityAdCodecTest {

    @Test
    fun roundTripsIdentity() {
        val payload = ProximityAdCodec.encode("dev-abcdef0123", "Galaxy")
        assertNotNull(payload)
        val decoded = ProximityAdCodec.decode(payload)
        assertNotNull(decoded)
        assertEquals("dev-abcdef0123", decoded.first)
        assertEquals("Galaxy", decoded.second)
    }

    @Test
    fun displayNameIsTruncatedToFit() {
        // deviceId (14) + version + sep leaves 11 bytes in a 27-byte budget.
        val payload = ProximityAdCodec.encode("dev-abcdef0123", "a very long display name")
        assertNotNull(payload)
        assertTrue(payload.size <= ProximityAdCodec.MAX_AD_BYTES)
        val decoded = ProximityAdCodec.decode(payload)
        assertNotNull(decoded)
        assertEquals("dev-abcdef0123", decoded.first)
        assertEquals("a very long", decoded.second)
    }

    @Test
    fun emptyDisplayNameRoundTrips() {
        val payload = ProximityAdCodec.encode("dev-abcdef0123", "")
        assertNotNull(payload)
        val decoded = ProximityAdCodec.decode(payload)
        assertNotNull(decoded)
        assertEquals("dev-abcdef0123", decoded.first)
        assertEquals("", decoded.second)
    }

    @Test
    fun unencodableDeviceIdReturnsNull() {
        // 60-char id cannot fit even alone.
        assertNull(ProximityAdCodec.encode("dev-" + "a".repeat(60), "name"))
        // Enforce the caller's budget too.
        assertNull(ProximityAdCodec.encode("dev-abcdef0123", "name", maxBytes = 8))
    }

    @Test
    fun malformedPayloadsAreRejected() {
        assertNull(ProximityAdCodec.decode(ByteArray(0)))
        assertNull(ProximityAdCodec.decode(byteArrayOf(0x02, 0x61, 0x62))) // wrong version
        assertNull(ProximityAdCodec.decode(byteArrayOf(0x01, 0x00, 0x61))) // no id
        assertNull(ProximityAdCodec.decode(byteArrayOf(0x01, 0x61))) // no separator
    }
}
