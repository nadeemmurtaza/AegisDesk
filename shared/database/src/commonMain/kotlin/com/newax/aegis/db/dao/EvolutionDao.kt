package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.*

/**
 * Persistence for the RLAIF-E self-learning layer (docs/AGENTS_DESIGN.md
 * §evolution — schema v19): the Evolution Ledger (`skill_evolution`), the
 * Staging Registry Database (`staging_records`), and the reward pipeline
 * (`learning_signals`). Device-local like the agents/skills tables — what
 * this device learned is recorded here; the mesh carries the episodes and
 * library entries those learnings produce.
 */
@Dao
interface EvolutionDao {

    // ── Evolution Ledger ────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvolution(entry: SkillEvolution): Long

    @Query("SELECT * FROM skill_evolution WHERE skillId = :skillId ORDER BY updatedAtMs DESC")
    suspend fun evolutionsForSkill(skillId: String): List<SkillEvolution>

    @Query("SELECT * FROM skill_evolution WHERE skillId = :skillId AND methodId = :methodId LIMIT 1")
    suspend fun method(skillId: String, methodId: String): SkillEvolution?

    @Query("SELECT * FROM skill_evolution WHERE skillId = :skillId AND status = 'ACTIVE' ORDER BY confidence DESC")
    suspend fun activeMethods(skillId: String): List<SkillEvolution>

    @Query("SELECT * FROM skill_evolution WHERE skillId = :skillId AND status = 'STAGED' ORDER BY createdAtMs DESC")
    suspend fun stagedMethods(skillId: String): List<SkillEvolution>

    @Query("SELECT * FROM skill_evolution ORDER BY updatedAtMs DESC LIMIT :limit")
    suspend fun recentEvolution(limit: Int = 100): List<SkillEvolution>

    @Query("UPDATE skill_evolution SET status = :status, updatedAtMs = :now WHERE id = :id")
    suspend fun setEvolutionStatus(id: Long, status: String, now: Long): Int

    @Query(
        "UPDATE skill_evolution SET executionCount = :exec, successCount = :ok, failureCount = :fail, " +
            "totalLatencyMs = :total, avgLatencyMs = :avg, confidence = :confidence, " +
            "lastOutcome = :lastOutcome, lastError = :lastError, updatedAtMs = :now WHERE id = :id"
    )
    suspend fun recordOutcome(
        id: Long, exec: Int, ok: Int, fail: Int, total: Long, avg: Long,
        confidence: Double, lastOutcome: String, lastError: String, now: Long
    ): Int

    @Query("DELETE FROM skill_evolution WHERE skillId = :skillId")
    suspend fun clearSkillEvolution(skillId: String): Int

    // ── Staging Registry ────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStaging(record: StagingRecord)

    @Query("SELECT * FROM staging_records WHERE stagingId = :stagingId LIMIT 1")
    suspend fun stagingById(stagingId: String): StagingRecord?

    @Query("SELECT * FROM staging_records WHERE status = 'PENDING_USER_APPROVAL' ORDER BY createdAtMs DESC")
    suspend fun pendingStaging(): List<StagingRecord>

    @Query("SELECT COUNT(*) FROM staging_records WHERE status = 'PENDING_USER_APPROVAL'")
    suspend fun pendingStagingCount(): Int

    @Query("UPDATE staging_records SET status = :status, decidedAtMs = :now WHERE stagingId = :stagingId")
    suspend fun setStagingStatus(stagingId: String, status: String, now: Long): Int

    @Query("SELECT * FROM staging_records WHERE status != 'PENDING_USER_APPROVAL' ORDER BY decidedAtMs DESC LIMIT :limit")
    suspend fun recentStaging(limit: Int = 50): List<StagingRecord>

    @Query("DELETE FROM staging_records WHERE stagingId = :stagingId")
    suspend fun deleteStaging(stagingId: String): Int

    // ── Learning signals (RLAIF reward pipeline) ────────────────────────────

    @Insert
    suspend fun insertSignal(signal: LearningSignal): Long

    @Query("SELECT * FROM learning_signals ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun recentSignals(limit: Int = 100): List<LearningSignal>

    @Query("SELECT * FROM learning_signals WHERE skillId = :skillId ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun signalsForSkill(skillId: String, limit: Int = 50): List<LearningSignal>

    @Query("SELECT * FROM learning_signals WHERE consumed = 0 ORDER BY createdAtMs ASC LIMIT :limit")
    suspend fun unconsumedSignals(limit: Int = 50): List<LearningSignal>

    @Query("SELECT COUNT(*) FROM learning_signals WHERE consumed = 0")
    suspend fun unconsumedCount(): Int

    @Query("UPDATE learning_signals SET consumed = 1 WHERE id = :id")
    suspend fun markConsumed(id: Long): Int
}
