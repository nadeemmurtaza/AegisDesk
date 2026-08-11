package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.SyncJournalEntity

/**
 * Journal persistence for the sync engine (docs/SYNC_DESIGN.md §4).
 * All inserts dedup by opId (IGNORE) — the CRDT guarantee that applying the
 * same entry via multiple peers applies it exactly once.
 */
@Dao
interface SyncJournalDao {

    /** Append one entry; returns 1 if new, 0 if already present (dedup). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: SyncJournalEntity): Long

    /** Append a batch; rows that already exist are skipped (dedup). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<SyncJournalEntity>)

    /** Full journal in merge order (hlc, then device). */
    @Query("SELECT * FROM sync_journal ORDER BY hlcWall ASC, hlcCounter ASC")
    suspend fun getAll(): List<SyncJournalEntity>

    /** Exact-op lookup — used to confirm dedup and by tests. */
    @Query("SELECT * FROM sync_journal WHERE opId = :opId LIMIT 1")
    suspend fun getByOpId(opId: String): SyncJournalEntity?

    /** Delta scan: every entry strictly after the given (hlcWall, hlcCounter). */
    @Query(
        "SELECT * FROM sync_journal WHERE hlcWall > :wall OR (hlcWall = :wall AND hlcCounter > :counter) " +
            "ORDER BY hlcWall ASC, hlcCounter ASC"
    )
    suspend fun entriesAfter(wall: Long, counter: Long): List<SyncJournalEntity>

    /** All mutations of one record — the RECORD-kind version history. */
    @Query(
        "SELECT * FROM sync_journal WHERE tableName = :tableName AND key = :key " +
            "ORDER BY hlcWall ASC, hlcCounter ASC"
    )
    suspend fun entriesFor(tableName: String, key: String): List<SyncJournalEntity>

    @Query("SELECT COUNT(*) FROM sync_journal")
    suspend fun count(): Long

    /** GC: drop journal entries older than the given hlc (all peers acked past it). */
    @Query("DELETE FROM sync_journal WHERE hlcWall < :wall OR (hlcWall = :wall AND hlcCounter < :counter)")
    suspend fun deleteBefore(wall: Long, counter: Long): Int
}
