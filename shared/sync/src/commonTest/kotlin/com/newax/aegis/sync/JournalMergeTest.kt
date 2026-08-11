package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JournalMergeTest {

    private fun entry(
        opId: String,
        deviceId: String,
        wall: Long,
        counter: Long,
        kind: SyncEntry.Kind = SyncEntry.Kind.RECORD,
        table: String = "persons",
        key: String = "1",
        tombstone: Boolean = false
    ) = SyncEntry.of(opId, deviceId, Hlc(wall, counter), kind, table, key, tombstone = tombstone)

    @Test
    fun mergeDeduplicatesByOpId() {
        val existing = setOf("a", "b")
        val incoming = listOf(entry("b", "w", 2, 0), entry("c", "w", 3, 0), entry("a", "m", 1, 0))
        val result = JournalMerge.merge(existing, incoming)
        assertEquals(listOf("c"), result.newEntries.map { it.opId })
        assertEquals(2, result.duplicates)
    }

    @Test
    fun mergeReturnsMergeOrder() {
        val incoming = listOf(entry("c", "w", 3, 0), entry("a", "w", 1, 0), entry("b", "w", 2, 0))
        val result = JournalMerge.merge(emptySet(), incoming)
        assertEquals(listOf("a", "b", "c"), result.newEntries.map { it.opId })
    }

    @Test
    fun logAppendIsIdempotent() {
        val log = entry("l1", "w", 1, 0, kind = SyncEntry.Kind.LOG, table = "memory_records", key = "stream")
        val first = JournalMerge.merge(emptySet(), listOf(log))
        val second = JournalMerge.merge(first.newEntries.map { it.opId }.toSet(), listOf(log))
        assertEquals(1, first.newEntries.size)
        assertEquals(0, second.newEntries.size)
        assertEquals(1, second.duplicates)
    }

    @Test
    fun recordWinnerIsMaxHlc() {
        val older = entry("a", "w", 1, 0, key = "9", table = "persons")
        val newer = entry("b", "w", 2, 0, key = "9", table = "persons")
        val state = RecordResolver.resolve(listOf(older, newer))
        assertIs<RecordState.Live>(state)
        assertEquals("b", state.entry.opId)
    }

    @Test
    fun recordTieBreakIsDeviceId() {
        val a = entry("a", "dev-a", 5, 0, key = "9")
        val b = entry("b", "dev-b", 5, 0, key = "9")
        val state = RecordResolver.resolve(listOf(a, b))
        assertIs<RecordState.Live>(state)
        assertEquals("dev-b", state.entry.deviceId) // lexicographic tie-break
    }

    @Test
    fun tombstoneWinsOverOlderState() {
        val live = entry("a", "w", 1, 0, key = "9")
        val deleted = entry("b", "w", 2, 0, key = "9", tombstone = true)
        assertEquals(RecordState.Deleted, RecordResolver.resolve(listOf(live, deleted)))
    }

    @Test
    fun absentWhenNoHistory() {
        assertEquals(RecordState.Absent, RecordResolver.resolve(emptyList()))
    }

    @Test
    fun newerStateAfterTombstoneWins() {
        // A record deleted then re-created: the re-creation is a normal entry
        // with a higher hlc, so it wins over the tombstone.
        val live = entry("a", "w", 1, 0, key = "9")
        val deleted = entry("b", "w", 2, 0, key = "9", tombstone = true)
        val resurrected = entry("c", "w", 3, 0, key = "9")
        val state = RecordResolver.resolve(listOf(live, deleted, resurrected))
        assertIs<RecordState.Live>(state)
        assertEquals("c", state.entry.opId)
        assertTrue(!state.entry.tombstone)
    }
}
