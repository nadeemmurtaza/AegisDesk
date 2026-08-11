package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.*

/**
 * Persistence for the agent runtime layer (docs/AGENTS_DESIGN.md §runtime):
 * the run ledger (`agent_sessions`) and the health-audit ledger
 * (`agent_health`). Device-local like the agents/skills tables — a session is
 * a record of what this device's copy of an agent did, never synced.
 */
@Dao
interface AgentRuntimeDao {

    // ── sessions ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: AgentSessionEntity)

    @Query("SELECT * FROM agent_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun sessionById(sessionId: String): AgentSessionEntity?

    @Query("SELECT * FROM agent_sessions WHERE status = 'RUNNING' ORDER BY startedAtMs DESC")
    suspend fun activeSessions(): List<AgentSessionEntity>

    @Query("SELECT * FROM agent_sessions WHERE status = 'FROZEN' ORDER BY updatedAtMs DESC")
    suspend fun frozenSessions(): List<AgentSessionEntity>

    @Query("SELECT * FROM agent_sessions ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun recentSessions(limit: Int = 50): List<AgentSessionEntity>

    @Query("SELECT * FROM agent_sessions WHERE agentId = :agentId AND status = 'FROZEN' ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun latestFrozenForAgent(agentId: String): AgentSessionEntity?

    @Query("UPDATE agent_sessions SET phase = :phase, updatedAtMs = :now WHERE sessionId = :sessionId")
    suspend fun setPhase(sessionId: String, phase: String, now: Long): Int

    @Query("UPDATE agent_sessions SET resultJson = :resultJson, status = 'COMPLETED', phase = 'DONE', updatedAtMs = :now WHERE sessionId = :sessionId")
    suspend fun setResult(sessionId: String, resultJson: String, now: Long): Int

    @Query("UPDATE agent_sessions SET errorJson = :errorJson, status = 'FAILED', updatedAtMs = :now WHERE sessionId = :sessionId")
    suspend fun setError(sessionId: String, errorJson: String, now: Long): Int

    /** Abort keeps status ABORTED (never FAILED) — the uniform USER_ABORT error block is attached. */
    @Query("UPDATE agent_sessions SET errorJson = :errorJson, status = 'ABORTED', phase = 'DONE', updatedAtMs = :now WHERE sessionId = :sessionId")
    suspend fun setAborted(sessionId: String, errorJson: String, now: Long): Int

    @Query("UPDATE agent_sessions SET status = 'FROZEN', frozenPath = :path, updatedAtMs = :now WHERE sessionId = :sessionId")
    suspend fun setFrozen(sessionId: String, path: String, now: Long): Int

    /** Crash residue: any RUNNING session older than [olderThan] is dead — mark it FAILED. */
    @Query("UPDATE agent_sessions SET status = 'FAILED' WHERE status = 'RUNNING' AND updatedAtMs < :olderThan")
    suspend fun markStaleRunning(olderThan: Long): Int

    @Query("DELETE FROM agent_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String): Int

    // ── health ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHealth(health: AgentHealthEntity)

    @Query("SELECT * FROM agent_health WHERE agentId = :agentId LIMIT 1")
    suspend fun healthById(agentId: String): AgentHealthEntity?

    @Query("SELECT * FROM agent_health ORDER BY lastCheckAtMs DESC")
    suspend fun allHealth(): List<AgentHealthEntity>
}
