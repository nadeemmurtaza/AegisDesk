package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.*

/**
 * Persistence for the three-layer hierarchical agent memory
 * (docs/MEMORY_DESIGN.md). One DAO for all five tables, mirroring the grouped
 * AppRegistryDao / PersonRegistryDao precedent.
 *
 * - Scratchpad is device-local by design (isolation) — no sync columns, no
 *   journaling.
 * - work_log is device-local (the swarm shares one DB) — dedupe by
 *   (action, resource).
 * - episodes / handoffs / library_entries carry sync metadata; journal
 *   capture + materialize live in SyncRuntime / DesktopSync.
 */
@Dao
interface AgentMemoryDao {

    // ── L2 Scratchpad (private, local-only) ─────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putScratchpad(entry: AgentScratchpad)

    @Query("DELETE FROM agent_scratchpad WHERE agentId = :agentId AND key = :key")
    suspend fun deleteScratchpad(agentId: String, key: String): Int

    @Query("DELETE FROM agent_scratchpad WHERE agentId = :agentId")
    suspend fun clearScratchpad(agentId: String): Int

    @Query("SELECT * FROM agent_scratchpad WHERE agentId = :agentId ORDER BY updatedAtMs DESC")
    suspend fun scratchpadFor(agentId: String): List<AgentScratchpad>

    @Query("DELETE FROM agent_scratchpad WHERE expiresAtMs > 0 AND expiresAtMs < :now")
    suspend fun pruneExpiredScratchpad(now: Long): Int

    // ── Episodic (chronological, outcome + lesson) ──────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisode(episode: Episode): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisode(episode: Episode)

    @Query("SELECT * FROM episodes WHERE episodeId = :episodeId LIMIT 1")
    suspend fun episodeById(episodeId: String): Episode?

    @Query("DELETE FROM episodes WHERE episodeId = :episodeId")
    suspend fun deleteEpisode(episodeId: String): Int

    @Query("SELECT * FROM episodes ORDER BY occurredAtMs DESC LIMIT :limit")
    suspend fun recentEpisodes(limit: Int = 50): List<Episode>

    @Query("SELECT * FROM episodes WHERE agentId = :agentId ORDER BY occurredAtMs DESC LIMIT :limit")
    suspend fun episodesForAgent(agentId: String, limit: Int = 50): List<Episode>

    @Query("SELECT * FROM episodes WHERE outcome = 'FAILURE' ORDER BY occurredAtMs DESC LIMIT :limit")
    suspend fun lessonsLearned(limit: Int = 50): List<Episode>

    // ── L3 Handoffs (shared write, pointers) ────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHandoff(handoff: HandoffEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHandoff(handoff: HandoffEntry)

    @Query("SELECT * FROM handoffs WHERE handoffId = :handoffId LIMIT 1")
    suspend fun handoffById(handoffId: String): HandoffEntry?

    @Query("DELETE FROM handoffs WHERE handoffId = :handoffId")
    suspend fun deleteHandoff(handoffId: String): Int

    @Query("UPDATE handoffs SET status = :status WHERE handoffId = :handoffId")
    suspend fun updateHandoffStatus(handoffId: String, status: String): Int

    @Query("SELECT * FROM handoffs WHERE toAgent = :agent AND status = 'PENDING' ORDER BY createdAtMs DESC")
    suspend fun handoffInbox(agent: String): List<HandoffEntry>

    @Query("SELECT * FROM handoffs WHERE fromAgent = :agent ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun handoffOutbox(agent: String, limit: Int = 50): List<HandoffEntry>

    // ── Work log (zero duplication, local) ──────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWork(entry: WorkLogEntry): Long

    @Query("SELECT * FROM work_log WHERE action = :action AND resource = :resource LIMIT 1")
    suspend fun workFor(action: String, resource: String): WorkLogEntry?

    @Query("UPDATE work_log SET status = :status, atMs = :now WHERE action = :action AND resource = :resource")
    suspend fun updateWork(action: String, resource: String, status: String, now: Long): Int

    @Query("SELECT * FROM work_log ORDER BY atMs DESC LIMIT :limit")
    suspend fun recentWork(limit: Int = 50): List<WorkLogEntry>

    // ── L1 Global Library (gated) ───────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLibrary(entry: LibraryEntry)

    @Query("SELECT * FROM library_entries WHERE entryId = :entryId LIMIT 1")
    suspend fun libraryById(entryId: String): LibraryEntry?

    @Query("DELETE FROM library_entries WHERE entryId = :entryId")
    suspend fun deleteLibrary(entryId: String): Int

    @Query("UPDATE library_entries SET status = :status, decidedAtMs = :now WHERE entryId = :entryId")
    suspend fun setLibraryStatus(entryId: String, status: String, now: Long): Int

    @Query("SELECT * FROM library_entries WHERE status = 'ACTIVE' ORDER BY category ASC, confidence DESC")
    suspend fun activeLibrary(): List<LibraryEntry>

    @Query("SELECT * FROM library_entries WHERE status = :status ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun libraryByStatus(status: String, limit: Int = 100): List<LibraryEntry>

    @Query("SELECT * FROM library_entries WHERE status = 'ACTIVE' AND category = :category ORDER BY confidence DESC")
    suspend fun activeLibraryCategory(category: String): List<LibraryEntry>

    /**
     * Keyword recall — the deterministic, offline-first retrieval path
     * (repo invariant: exact lookup before loading a model). Returns only the
     * tiny relevant snippets an agent needs, not whole histories.
     */
    @Query(
        "SELECT * FROM library_entries WHERE status = 'ACTIVE' AND " +
            "(title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' OR category LIKE '%' || :q || '%') " +
            "ORDER BY confidence DESC LIMIT :limit"
    )
    suspend fun recall(q: String, limit: Int = 5): List<LibraryEntry>
}
