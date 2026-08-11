package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The sync journal — the append-only CRDT log every device writes and every
 * peer pulls from (docs/SYNC_DESIGN.md §4.1). One row per mutation of a
 * syncable table; merge semantics are decided by [kind]:
 *
 * - LOG: pure append — idempotent union by [opId], nothing is ever deleted.
 * - RECORD: last-writer-wins per [key] — the entry with the max (hlcWall,
 *   hlcCounter) wins, and [tombstone] marks a delete that outranks any older
 *   state.
 *
 * [payload] holds the encrypted record/log delta (BLOB; wrapped at the
 * session-crypto layer, never plaintext).
 */
@Entity(
    tableName = "sync_journal",
    indices = [
        Index("tableName"),
        Index(value = ["tableName", "key"]),
        Index("deviceId"),
        Index(value = ["hlcWall", "hlcCounter"])
    ]
)
data class SyncJournalEntity(
    /** Unique forever (UUID) — the dedup key: the same entry arriving via
     *  multiple peers (W→M→I) is applied exactly once. */
    @PrimaryKey val opId: String,
    /** Writing device's identity id. */
    val deviceId: String,
    /** Hybrid logical clock — wall component (display/order). */
    @ColumnInfo(defaultValue = "0")
    val hlcWall: Long = 0,
    /** Hybrid logical clock — counter component (merge ordering, clock-skew proof). */
    @ColumnInfo(defaultValue = "0")
    val hlcCounter: Long = 0,
    /** LOG | RECORD — decides the merge rule (docs/SYNC_DESIGN.md §4.1). */
    val kind: String,
    /** Syncable table this entry mutates (memory_records, persons, kv_store, …). */
    val tableName: String,
    /** Record primary key (or log stream id) within [tableName]. */
    val key: String,
    /** Encrypted delta/record state — the journal never stores plaintext. */
    val payload: ByteArray,
    /** RECORD-kind delete marker; LOG entries never carry it. */
    @ColumnInfo(defaultValue = "0")
    val tombstone: Boolean = false,
    /** Wall-clock creation time for display (never used for merge order). */
    val createdAt: Long = currentTimeMillis()
) {
    companion object {
        const val KIND_LOG = "LOG"
        const val KIND_RECORD = "RECORD"
    }
}
