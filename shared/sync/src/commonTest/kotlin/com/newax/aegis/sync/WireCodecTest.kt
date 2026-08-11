package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WireCodecTest {

    private fun entry(
        opId: String,
        deviceId: String,
        wall: Long,
        counter: Long,
        kind: SyncEntry.Kind = SyncEntry.Kind.RECORD,
        table: String,
        key: String,
        payload: ByteArray = byteArrayOf(),
        tombstone: Boolean = false,
        createdAt: Long = wall
    ) = SyncEntry(opId, deviceId, Hlc(wall, counter), kind, table, key, payload, tombstone, createdAt)

    private fun assertEntryEqual(a: SyncEntry, b: SyncEntry) {
        assertEquals(a.opId, b.opId)
        assertEquals(a.deviceId, b.deviceId)
        assertEquals(a.hlc, b.hlc)
        assertEquals(a.kind, b.kind)
        assertEquals(a.table, b.table)
        assertEquals(a.key, b.key)
        assertContentEquals(a.payload, b.payload)
        assertEquals(a.tombstone, b.tombstone)
        assertEquals(a.createdAt, b.createdAt)
    }

    @Test
    fun vectorRoundTrip() {
        val msg = WireCodec.SyncMessage.VectorExchange(
            deviceId = "dev-w",
            watermarks = mapOf("dev-m" to Hlc(4, 2), "dev-i" to Hlc(1, 0))
        )
        val decoded = assertIs<WireCodec.SyncMessage.VectorExchange>(WireCodec.decode(WireCodec.encode(msg)))
        assertEquals(msg, decoded)
    }

    @Test
    fun vectorRoundTripEmptyAndEscaped() {
        val msg = WireCodec.SyncMessage.VectorExchange("dev|a\\b", emptyMap())
        val decoded = assertIs<WireCodec.SyncMessage.VectorExchange>(WireCodec.decode(WireCodec.encode(msg)))
        assertEquals("dev|a\\b", decoded.deviceId)
        assertEquals(emptyMap(), decoded.watermarks)
    }

    @Test
    fun deltaRoundTrip() {
        val entries = listOf(
            entry("op-1", "dev-w", 1, 0, SyncEntry.Kind.LOG, table = "memory_records", key = "stream",
                payload = byteArrayOf(0, 1, -1, 127, -128)),
            entry("op-2", "dev-m", 2, 4, SyncEntry.Kind.RECORD, table = "persons", key = "9",
                tombstone = true, createdAt = 77)
        )
        val msg = WireCodec.SyncMessage.Delta("dev-w", Hlc(1, 0), entries)
        val decoded = assertIs<WireCodec.SyncMessage.Delta>(WireCodec.decode(WireCodec.encode(msg)))
        assertEquals("dev-w", decoded.deviceId)
        assertEquals(Hlc(1, 0), decoded.fromHlc)
        assertEquals(2, decoded.entries.size)
        decoded.entries.zip(entries).forEach { (a, b) -> assertEntryEqual(a, b) }
    }

    @Test
    fun deltaEscapesSpecialCharacters() {
        val entries = listOf(
            entry("op|weird\\name", "dev\nw", 1, 0, SyncEntry.Kind.LOG, table = "memory_records", key = "line\n1")
        )
        val msg = WireCodec.SyncMessage.Delta("dev-w", Hlc(0, 0), entries)
        val decoded = assertIs<WireCodec.SyncMessage.Delta>(WireCodec.decode(WireCodec.encode(msg)))
        assertEntryEqual(decoded.entries[0], entries[0])
    }

    @Test
    fun ackRoundTrip() {
        val msg = WireCodec.SyncMessage.AckVector("dev-i", Hlc(9, 3))
        val decoded = assertIs<WireCodec.SyncMessage.AckVector>(WireCodec.decode(WireCodec.encode(msg)))
        assertEquals(msg, decoded)
    }

    @Test
    fun malformedInputsReturnNull() {
        assertNull(WireCodec.decode(""))                                   // empty
        assertNull(WireCodec.decode("X|garbage"))                          // unknown type
        assertNull(WireCodec.decode("V|dev"))                              // missing fields
        assertNull(WireCodec.decode("V|dev|dev-m:5"))                      // bad hlc shape
        assertNull(WireCodec.decode("V|dev|dev-m:-1:0"))                   // negative hlc
        assertNull(WireCodec.decode("V|dev\\"))                            // dangling escape
        assertNull(WireCodec.decode("A|dev|5:zz"))                         // non-numeric counter
        assertNull(WireCodec.decode("D|dev|5"))                            // missing ':' — must NOT crash
        assertNull(WireCodec.decode("D|dev"))                              // truncated delta
        assertNull(WireCodec.decode("D"))                                  // bare type char
        // Bad entry inside a delta: unknown kind
        val badDelta = "D|dev|0:0\u001eop|dev|1|0|NOPE|t|k||0|0"
        assertNull(WireCodec.decode(badDelta))
        // Odd-length hex payload
        val badHex = "D|dev|0:0\u001eop|dev|1|0|RECORD|t|k|abc|0|0"
        assertNull(WireCodec.decode(badHex))
        // Non-hex payload character
        val badHexChar = "D|dev|0:0\u001eop|dev|1|0|RECORD|t|k|0g|0|0"
        assertNull(WireCodec.decode(badHexChar))
        // Entry with too many fields
        val tooMany = "D|dev|0:0\u001eop|dev|1|0|RECORD|t|k||0|0|extra"
        assertNull(WireCodec.decode(tooMany))
    }

    @Test
    fun recordSeparatorInFieldIsEscapedAndRoundTrips() {
        val key = "a\u001eb" // record separator inside a key
        val entries = listOf(entry("op-1", "dev-w", 1, 0, SyncEntry.Kind.RECORD, table = "persons", key = key))
        val msg = WireCodec.SyncMessage.Delta("dev-w", Hlc(0, 0), entries)
        val decoded = assertIs<WireCodec.SyncMessage.Delta>(WireCodec.decode(WireCodec.encode(msg)))
        assertEquals(key, decoded.entries[0].key)
    }

    @Test
    fun hexRoundTrip() {
        val bytes = byteArrayOf(0, 1, 2, 15, 16, 127, -1, -128, 90)
        val encoded = Hex.encode(bytes)
        assertEquals("0001020f107fff8090".lowercase(), encoded)
        assertContentEquals(bytes, Hex.decode(encoded)!!)
        assertNull(Hex.decode("abc"))
        assertNull(Hex.decode("zz"))
    }
}
