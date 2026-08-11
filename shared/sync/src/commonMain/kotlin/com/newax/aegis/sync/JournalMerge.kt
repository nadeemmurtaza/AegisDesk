package com.newax.aegis.sync

/**
 * The merge core (docs/SYNC_DESIGN.md §4.1): two levels, because the journal
 * and the materialized tables follow different rules.
 *
 * 1. [merge] — the journal itself is an append-only G-CRDT: idempotent union
 *    by [SyncEntry.opId]. Every entry is stored even if a newer record
 *    supersedes it (the journal is the version history + audit trail).
 * 2. [RecordResolver] — the *state* of a RECORD is the entry with the max
 *    (hlc, deviceId); a tombstone entry wins over all older states because it
 *    participates in the same max comparison.
 */
object JournalMerge {

    /** Total order for LWW: hlc first, deviceId as the deterministic tie-break. */
    val COMPARATOR: Comparator<SyncEntry> = compareBy<SyncEntry>({ it.hlc }, { it.deviceId })

    /**
     * Dedup merge of [incoming] into a journal that already holds
     * [existingOpIds]. Returns the genuinely new entries in merge order and
     * the count of duplicates skipped. Pure — the caller persists [newEntries]
     * (via the wiring slice's SyncJournalDao) and advances vectors.
     */
    fun merge(existingOpIds: Set<String>, incoming: List<SyncEntry>): MergeResult {
        val newEntries = incoming
            .filter { it.opId !in existingOpIds }
            .sortedWith(COMPARATOR)
        return MergeResult(newEntries = newEntries, duplicates = incoming.size - newEntries.size)
    }
}

data class MergeResult(
    /** Entries not previously seen, in (hlc, deviceId) order. */
    val newEntries: List<SyncEntry>,
    /** Entries skipped because their opId was already present. */
    val duplicates: Int
)

/** The live state of one record (table, key) given its journal history. */
sealed interface RecordState {
    /** The winning entry — its payload IS the current record state. */
    data class Live(val entry: SyncEntry) : RecordState
    /** The winning entry was a tombstone — the record is deleted. */
    data object Deleted : RecordState
    /** No history at all. */
    data object Absent : RecordState
}

object RecordResolver {

    /** LWW resolution: max (hlc, deviceId) wins; tombstones are ordinary entries. */
    fun resolve(history: List<SyncEntry>): RecordState {
        val winner = history.maxWithOrNull(JournalMerge.COMPARATOR) ?: return RecordState.Absent
        return if (winner.tombstone) RecordState.Deleted else RecordState.Live(winner)
    }
}
