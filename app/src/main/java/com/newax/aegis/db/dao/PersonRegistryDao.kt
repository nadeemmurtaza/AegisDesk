package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.*

@Dao
interface PersonRegistryDao {

    // ── PersonSnapshot ────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSnapshot(snapshot: PersonSnapshot)

    @Query("SELECT * FROM person_snapshots WHERE personEntityId = :id LIMIT 1")
    fun snapshot(id: Long): PersonSnapshot?

    @Query("SELECT * FROM person_snapshots ORDER BY importanceScore DESC, lastInteractionMs DESC LIMIT :limit")
    fun hotPersons(limit: Int = 20): List<PersonSnapshot>

    @Query("UPDATE person_snapshots SET pendingCommitmentCount = :count, snapshotUpdatedMs = :now WHERE personEntityId = :id")
    fun updateCommitmentCount(id: Long, count: Int, now: Long)

    @Query("UPDATE person_snapshots SET lastInteractionMs = :ts, snapshotUpdatedMs = :now WHERE personEntityId = :id")
    fun touchInteraction(id: Long, ts: Long, now: Long)

    // ── PersonPolicy ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertPolicy(policy: PersonPolicy)

    @Query("SELECT * FROM person_policies WHERE personEntityId = :id LIMIT 1")
    fun policy(id: Long): PersonPolicy?

    // ── PersonChannelPref ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertChannelPref(pref: PersonChannelPref)

    @Query("""
        SELECT * FROM person_channel_prefs
        WHERE personEntityId = :id
        ORDER BY
            CASE WHEN taskContext = :context THEN 0 ELSE 1 END,
            probability DESC
        LIMIT 1
    """)
    fun bestChannel(id: Long, context: String): PersonChannelPref?

    @Query("SELECT * FROM person_channel_prefs WHERE personEntityId = :id ORDER BY probability DESC")
    fun allChannels(id: Long): List<PersonChannelPref>

    @Query("""
        UPDATE person_channel_prefs
        SET probability = MIN(0.99, probability + 0.02),
            evidenceCount = evidenceCount + 1,
            lastUpdatedMs = :now
        WHERE personEntityId = :id AND taskContext = :context
    """)
    fun reinforceChannel(id: Long, context: String, now: Long)

    @Query("""
        UPDATE person_channel_prefs
        SET probability = MAX(0.01, probability - 0.05),
            lastUpdatedMs = :now
        WHERE personEntityId = :id AND taskContext = :context
    """)
    fun penalizeChannel(id: Long, context: String, now: Long)

    // ── Commitment ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCommitment(c: Commitment): Long

    @Query("SELECT * FROM commitments WHERE status = 'pending' AND debtorPersonId = :personId ORDER BY dueMs ASC LIMIT :limit")
    fun pendingByDebtor(personId: Long, limit: Int = 10): List<Commitment>

    @Query("SELECT * FROM commitments WHERE status = 'pending' AND creditorPersonId = :personId ORDER BY dueMs ASC LIMIT :limit")
    fun pendingByCreditor(personId: Long, limit: Int = 10): List<Commitment>

    @Query("SELECT * FROM commitments WHERE status = 'pending' AND (debtorPersonId IS NULL OR creditorPersonId IS NULL) ORDER BY dueMs ASC LIMIT :limit")
    fun userCommitments(limit: Int = 20): List<Commitment>

    @Query("SELECT COUNT(*) FROM commitments WHERE status = 'pending' AND debtorPersonId = :personId")
    fun pendingCountByDebtor(personId: Long): Int

    @Query("UPDATE commitments SET status = :status, resolvedMs = :now WHERE id = :id")
    fun updateStatus(id: Long, status: String, now: Long)

    @Query("SELECT * FROM commitments WHERE status = 'pending' AND dueMs IS NOT NULL AND dueMs < :now ORDER BY dueMs ASC")
    fun overdueCommitments(now: Long): List<Commitment>
}
