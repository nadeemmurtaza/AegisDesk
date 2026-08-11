package com.newax.aegis.sync

/**
 * One journal entry — the engine's domain model (docs/SYNC_DESIGN.md §4.1).
 * This is the pure-KMP twin of the Room `SyncJournalEntity` in shared/database;
 * shared/sync deliberately does NOT depend on shared/database (Track I can't
 * use Room this round), so the mapping lives in the wiring slice.
 *
 * Merge semantics by [kind]:
 * - LOG: pure append — dedup by [opId], nothing is ever deleted.
 * - RECORD: last-writer-wins per (table, key) — max (hlc, deviceId) wins;
 *   [tombstone] marks a delete that outranks any older state.
 */
data class SyncEntry(
    /** Unique forever (UUID) — the dedup key across peers. */
    val opId: String,
    /** Writing device's identity id. */
    val deviceId: String,
    /** Hybrid logical clock — merge ordering, skew-proof. */
    val hlc: Hlc,
    /** LOG | RECORD — decides the merge rule. */
    val kind: Kind,
    /** Syncable table this entry mutates. */
    val table: String,
    /** Record primary key (or log stream id) within [table]. */
    val key: String,
    /** Opaque delta/record state (encrypted blob at the session-crypto layer). */
    val payload: ByteArray = ByteArray(0),
    /** RECORD-kind delete marker. */
    val tombstone: Boolean = false,
    /** Wall-clock creation for display only — never used for merge order. */
    val createdAt: Long = 0L
) {
    enum class Kind { LOG, RECORD }

    /** True when [other] carries the same mutation (same op, same author). */
    fun sameOp(other: SyncEntry): Boolean = opId == other.opId

    companion object {
        /** Test/construction helper with sensible defaults. */
        fun of(
            opId: String,
            deviceId: String,
            hlc: Hlc,
            kind: Kind = Kind.RECORD,
            table: String,
            key: String,
            payload: ByteArray = ByteArray(0),
            tombstone: Boolean = false,
            createdAt: Long = hlc.wall
        ): SyncEntry = SyncEntry(opId, deviceId, hlc, kind, table, key, payload, tombstone, createdAt)
    }
}
