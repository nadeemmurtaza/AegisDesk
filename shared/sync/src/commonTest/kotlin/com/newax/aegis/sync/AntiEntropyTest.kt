package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AntiEntropyTest {

    /** Minimal in-memory device: sorted journal + per-peer version vector. */
    private class Device(val id: String) {
        val journal = mutableListOf<SyncEntry>()
        var vectors = VersionVector.EMPTY

        fun write(entry: SyncEntry) {
            journal.add(entry)
            journal.sortWith(JournalMerge.COMPARATOR)
        }

        fun opIds(): Set<String> = journal.map { it.opId }.toSet()

        /** One full sync round in both directions (the anti-entropy loop). */
        fun syncWith(other: Device) {
            // this → other
            val outbound = AntiEntropy.outboundDelta(journal, other.vectors.watermarkFor(id))
            val applied = AntiEntropy.applyDelta(other.opIds(), outbound, id)
            other.journal.addAll(applied.newEntries)
            other.journal.sortWith(JournalMerge.COMPARATOR)
            other.vectors = other.vectors.merge(applied.senderWatermark)

            // other → this
            val inbound = AntiEntropy.outboundDelta(other.journal, vectors.watermarkFor(other.id))
            val appliedHere = AntiEntropy.applyDelta(opIds(), inbound, other.id)
            journal.addAll(appliedHere.newEntries)
            journal.sortWith(JournalMerge.COMPARATOR)
            vectors = vectors.merge(appliedHere.senderWatermark)
        }
    }

    private fun entry(
        opId: String, deviceId: String, wall: Long, counter: Long,
        kind: SyncEntry.Kind = SyncEntry.Kind.RECORD, table: String = "persons", key: String = "1",
        tombstone: Boolean = false
    ) = SyncEntry.of(opId, deviceId, Hlc(wall, counter), kind, table, key, tombstone = tombstone)

    @Test
    fun outboundDeltaIsBoundedByPeerWatermark() {
        val journal = listOf(
            entry("a", "w", 1, 0, table = "persons", key = "1"),
            entry("b", "w", 2, 0, table = "persons", key = "2"),
            entry("c", "w", 3, 0, table = "persons", key = "3")
        )
        val delta = AntiEntropy.outboundDelta(journal, Hlc(2, 0))
        assertEquals(listOf("c"), delta.map { it.opId })
    }

    @Test
    fun outboundDeltaSendsAllWhenNoWatermark() {
        val journal = listOf(entry("a", "w", 1, 0), entry("b", "w", 2, 0))
        assertEquals(2, AntiEntropy.outboundDelta(journal, Hlc.ZERO).size)
    }

    @Test
    fun buildDeltaCarriesTheSameEntriesAndWatermark() {
        val journal = listOf(entry("a", "w", 1, 0), entry("b", "w", 2, 0), entry("c", "w", 3, 0))
        val delta = AntiEntropy.buildDelta("dev-w", journal, Hlc(2, 0))
        assertEquals(Hlc(2, 0), delta.fromHlc)
        assertEquals(listOf("c"), delta.entries.map { it.opId })
    }

    @Test
    fun applyDeltaDeduplicatesAndAdvancesWatermark() {
        val existing = setOf("a")
        val incoming = listOf(entry("a", "w", 1, 0), entry("b", "w", 2, 0), entry("c", "w", 3, 0))
        val result = AntiEntropy.applyDelta(existing, incoming, senderDeviceId = "dev-w")
        assertEquals(listOf("b", "c"), result.newEntries.map { it.opId })
        assertEquals(1, result.duplicates)
        assertEquals(Hlc(3, 0), result.senderWatermark.watermarkFor("dev-w"))
    }

    @Test
    fun applyDeltaWithAllDuplicatesKeepsWatermarkAtZero() {
        val incoming = listOf(entry("a", "w", 5, 0))
        val result = AntiEntropy.applyDelta(setOf("a"), incoming, "dev-w")
        assertEquals(0, result.newEntries.size)
        assertEquals(Hlc.ZERO, result.senderWatermark.watermarkFor("dev-w"))
    }

    /**
     * The design's flagship scenario (docs/SYNC_DESIGN.md §1): W writes while
     * A and I are offline; W syncs to M; I pulls from M; A pulls from I.
     * Every path must converge to the same journal — no master, no loss.
     */
    @Test
    fun meshConvergesOverAnyPath() {
        val w = Device("w")
        val m = Device("m")
        val i = Device("i")
        val a = Device("a")

        // W writes two entries while A and I are offline.
        w.write(entry("e1", "w", 1, 0, table = "memory_records", key = "stream", kind = SyncEntry.Kind.LOG))
        w.write(entry("e2", "w", 2, 0, table = "persons", key = "9"))

        // W comes online with M: data flows W → M.
        w.syncWith(m)
        assertEquals(setOf("e1", "e2"), m.opIds())
        assertEquals(setOf("e1", "e2"), w.opIds())

        // M writes its own entry, then I comes online and pulls from M.
        m.write(entry("e3", "m", 3, 0, table = "triples", key = "42"))
        i.syncWith(m)
        assertEquals(setOf("e1", "e2", "e3"), i.opIds())

        // A comes online and pulls from I — I, M, A all have the data.
        a.syncWith(i)
        assertEquals(setOf("e1", "e2", "e3"), a.opIds())

        // W comes back online and re-syncs with M — it catches e3 (its earlier
        // watermark for M was never advanced, so M resends everything).
        w.syncWith(m)
        assertEquals(setOf("e1", "e2", "e3"), w.opIds())

        // Every device converges to the identical journal content.
        val expected = listOf("e1", "e2", "e3")
        listOf(w, m, i, a).forEach { device ->
            assertEquals(expected, device.journal.map { it.opId })
        }
    }

    @Test
    fun concurrentWritesMergeWithoutLoss() {
        val w = Device("w")
        val m = Device("m")

        // Both write the SAME record key concurrently (LWW case).
        w.write(entry("w1", "w", 10, 0, key = "9"))
        m.write(entry("m1", "m", 10, 0, key = "9")) // same hlc — tie-break by deviceId

        w.syncWith(m)

        // Both journals hold both entries (history preserved)…
        listOf(w, m).forEach { assertEquals(setOf("w1", "m1"), it.opIds()) }
        // …and both resolve the same LWW winner.
        val winner = RecordResolver.resolve(w.journal).let { (it as RecordState.Live).entry }
        listOf(w, m).forEach { device ->
            val local = RecordResolver.resolve(device.journal) as RecordState.Live
            assertEquals(winner.opId, local.entry.opId)
        }
        assertEquals("w1", winner.opId) // max (hlc, deviceId) wins — "w" > "m"
    }

    @Test
    fun tombstonesPropagateAcrossTheMesh() {
        val w = Device("w")
        val m = Device("m")
        w.write(entry("live", "w", 1, 0, key = "9"))
        w.write(entry("del", "w", 2, 0, key = "9", tombstone = true))
        w.syncWith(m)
        assertEquals(RecordState.Deleted, RecordResolver.resolve(m.journal))
        // m's live read of the record must be Deleted too.
        assertTrue(RecordResolver.resolve(m.journal) is RecordState.Deleted)
    }
}
