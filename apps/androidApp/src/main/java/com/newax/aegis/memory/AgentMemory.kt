package com.newax.aegis.memory

import com.newax.aegis.SyncRuntime
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.AgentScratchpad
import com.newax.aegis.db.entity.Episode
import com.newax.aegis.db.entity.EpisodeOutcome
import com.newax.aegis.db.entity.HandoffEntry
import com.newax.aegis.db.entity.HandoffStatus
import com.newax.aegis.db.entity.LibraryEntry
import com.newax.aegis.db.entity.LibraryStatus
import com.newax.aegis.db.entity.WorkLogEntry
import com.newax.aegis.db.entity.WorkLogStatus
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * The three-layer hierarchical agent memory (docs/MEMORY_DESIGN.md):
 *
 *  L1 Global "Library"     — [library] shared, agent read-only. Writes land in
 *      [LibraryStatus.PENDING_APPROVAL] (the human-in-the-loop gate) and only
 *      reach ACTIVE on approval. [distill] resolves duplicates/conflicts in
 *      the background; [recall] returns only the tiny relevant snippets an
 *      agent needs (the cost lever — never whole chat histories).
 *  L2 Agent "Scratchpad"   — [scratchpadPut]/[scratchpadFor]: private and
 *      isolated per agent, TTL-scoped, LOCAL ONLY (never journaled, never
 *      synced — a Coding Agent's raw working state is nobody else's business).
 *  L3 "Handoff" state      — [createHandoff]/[handoffInbox]: shared-write
 *      structured artifacts with a clean summary + pointer. Agent A writes the
 *      artifact, Agent B reads the summary, not A's thoughts.
 *
 * Cross-cutting (the production-memory requirements):
 *  - Zero work duplication  → [claimWork]/[isWorkDone] (one (action, resource)
 *    done once; the swarm shares one device-local ledger).
 *  - Collective learning    → [recordEpisode] with outcome + lesson journals
 *    into the mesh (episodes sync; lessons learned arrive on every device).
 *  - Conflict resolution    → [distill] merges/overwrites in the background.
 *  - Atomicity              → every syncable write goes through the append-only
 *    journal (event sourcing; opId dedup, LWW per key) — never a bare table
 *    write.
 *  - Human-in-the-loop      → [submitKnowledge] requires [approveKnowledge]
 *    before the claim is visible to agents.
 *
 * All DB access is runBlocking + guarded, matching SyncRuntime's style; the
 * journal capture is best-effort and never breaks the local write.
 */
object AgentMemory {

    // ── L2 Scratchpad (private, isolated, TTL) ─────────────────────────────

    fun scratchpadPut(agentId: String, key: String, value: String, ttlMs: Long = 0L) {
        if (agentId.isBlank() || key.isBlank()) return
        val now = System.currentTimeMillis()
        runBlocking {
            runCatching {
                val db = AegisDatabase.get
                db.agentMemoryDao().pruneExpiredScratchpad(now)
                db.agentMemoryDao().putScratchpad(
                    AgentScratchpad(
                        agentId = agentId,
                        key = key,
                        value = value,
                        updatedAtMs = now,
                        expiresAtMs = if (ttlMs > 0) now + ttlMs else 0L
                    )
                )
            }
        }
    }

    fun scratchpadGet(agentId: String, key: String): String? = runBlocking {
        runCatching {
            AegisDatabase.get.agentMemoryDao().scratchpadFor(agentId)
                .firstOrNull { it.key == key }?.value
        }.getOrNull()
    }

    fun scratchpadFor(agentId: String): List<AgentScratchpad> = runBlocking {
        runCatching {
            val dao = AegisDatabase.get.agentMemoryDao()
            dao.pruneExpiredScratchpad(System.currentTimeMillis())
            dao.scratchpadFor(agentId)
        }.getOrDefault(emptyList())
    }

    fun scratchpadDelete(agentId: String, key: String) {
        runBlocking { runCatching { AegisDatabase.get.agentMemoryDao().deleteScratchpad(agentId, key) } }
    }

    fun scratchpadClear(agentId: String) {
        runBlocking { runCatching { AegisDatabase.get.agentMemoryDao().clearScratchpad(agentId) } }
    }

    // ── Episodic memory (the "periodic" layer: outcome + lesson) ───────────

    /**
     * Record one episode (chronological, temporal awareness). FAILURE episodes
     * carry a [lesson] — the distilled fix other agents inherit via the mesh.
     */
    fun recordEpisode(
        agentId: String,
        category: String,
        summary: String,
        outcome: String = EpisodeOutcome.OBSERVATION,
        lesson: String = "",
        contextRef: String = ""
    ): String {
        if (summary.isBlank()) return ""
        val episodeId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        runBlocking {
            runCatching {
                AegisDatabase.get.agentMemoryDao().insertEpisode(
                    Episode(
                        episodeId = episodeId,
                        agentId = agentId,
                        category = category,
                        summary = summary,
                        outcome = outcome,
                        lesson = lesson,
                        occurredAtMs = now,
                        contextRef = contextRef
                    )
                )
            }
        }
        SyncRuntime.captureRecord(
            SyncRuntime.TABLE_EPISODES, episodeId,
            listOf(
                "episodeId" to episodeId,
                "agentId" to agentId,
                "category" to category,
                "summary" to summary,
                "outcome" to outcome,
                "lesson" to lesson,
                "occurredAtMs" to now.toString(),
                "contextRef" to contextRef
            )
        )
        return episodeId
    }

    /** The chronological timeline — newest first. */
    fun recentEpisodes(limit: Int = 50): List<Episode> = runBlocking {
        runCatching { AegisDatabase.get.agentMemoryDao().recentEpisodes(limit) }.getOrDefault(emptyList())
    }

    /** FAILURE episodes with lessons — the collective-learning feed. */
    fun lessonsLearned(limit: Int = 50): List<Episode> = runBlocking {
        runCatching { AegisDatabase.get.agentMemoryDao().lessonsLearned(limit) }.getOrDefault(emptyList())
    }

    // ── L3 Handoffs (shared write, clean artifacts + pointers) ─────────────

    /** Agent A finishes a sub-task: writes the artifact and passes the pointer. */
    fun createHandoff(
        fromAgent: String,
        toAgent: String,
        task: String,
        summary: String,
        artifactJson: String = "{}",
        refId: String = ""
    ): String {
        if (fromAgent.isBlank() || toAgent.isBlank() || task.isBlank()) return ""
        val handoffId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        runBlocking {
            runCatching {
                AegisDatabase.get.agentMemoryDao().insertHandoff(
                    HandoffEntry(
                        handoffId = handoffId,
                        fromAgent = fromAgent,
                        toAgent = toAgent,
                        task = task,
                        summary = summary,
                        artifactJson = artifactJson,
                        status = HandoffStatus.PENDING,
                        refId = refId,
                        createdAtMs = now
                    )
                )
            }
        }
        SyncRuntime.captureRecord(
            SyncRuntime.TABLE_HANDOFFS, handoffId,
            listOf(
                "handoffId" to handoffId,
                "fromAgent" to fromAgent,
                "toAgent" to toAgent,
                "task" to task,
                "summary" to summary,
                "artifactJson" to artifactJson,
                "status" to HandoffStatus.PENDING,
                "refId" to refId,
                "createdAtMs" to now.toString()
            )
        )
        return handoffId
    }

    /** Agent B confirms it picked up the handoff — the ack propagates. */
    fun ackHandoff(handoffId: String) {
        if (handoffId.isBlank()) return
        runBlocking {
            runCatching { AegisDatabase.get.agentMemoryDao().updateHandoffStatus(handoffId, HandoffStatus.ACKED) }
        }
        SyncRuntime.captureRecord(
            SyncRuntime.TABLE_HANDOFFS, handoffId,
            listOf("handoffId" to handoffId, "status" to HandoffStatus.ACKED)
        )
    }

    fun handoffInbox(agent: String): List<HandoffEntry> = runBlocking {
        runCatching { AegisDatabase.get.agentMemoryDao().handoffInbox(agent) }.getOrDefault(emptyList())
    }

    fun handoffOutbox(agent: String, limit: Int = 50): List<HandoffEntry> = runBlocking {
        runCatching { AegisDatabase.get.agentMemoryDao().handoffOutbox(agent, limit) }.getOrDefault(emptyList())
    }

    // ── Work log (zero work duplication, device-local) ─────────────────────

    fun isWorkDone(action: String, resource: String): Boolean = runBlocking {
        runCatching {
            AegisDatabase.get.agentMemoryDao().workFor(action, resource)?.status == WorkLogStatus.DONE
        }.getOrDefault(false)
    }

    /** Claim a (action, resource) so no other agent redoes it. */
    fun claimWork(action: String, resource: String, agentId: String): Boolean {
        if (action.isBlank() || resource.isBlank()) return false
        val inserted = runBlocking {
            runCatching {
                AegisDatabase.get.agentMemoryDao().insertWork(
                    WorkLogEntry(action = action, resource = resource, agentId = agentId, status = WorkLogStatus.IN_PROGRESS)
                )
            }.getOrDefault(0L)
        }
        return inserted > 0
    }

    fun completeWork(action: String, resource: String) {
        runBlocking {
            runCatching {
                AegisDatabase.get.agentMemoryDao().updateWork(action, resource, WorkLogStatus.DONE, System.currentTimeMillis())
            }
        }
    }

    fun recentWork(limit: Int = 50): List<WorkLogEntry> = runBlocking {
        runCatching { AegisDatabase.get.agentMemoryDao().recentWork(limit) }.getOrDefault(emptyList())
    }

    // ── L1 Global Library (gated: PENDING → human approval → ACTIVE) ───────

    /**
     * A critical memory update lands BEHIND the validation gate — it is not
     * visible to agents until [approveKnowledge] promotes it (or [distill]
     * auto-approves a high-confidence non-conflicting claim).
     */
    fun submitKnowledge(category: String, title: String, content: String, confidence: Int = 80, source: String = "agent"): String {
        if (category.isBlank() || title.isBlank() || content.isBlank()) return ""
        val entryId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        runBlocking {
            runCatching {
                AegisDatabase.get.agentMemoryDao().upsertLibrary(
                    LibraryEntry(
                        entryId = entryId,
                        category = category,
                        title = title,
                        content = content,
                        confidence = confidence,
                        source = source,
                        status = LibraryStatus.PENDING_APPROVAL,
                        createdAtMs = now
                    )
                )
            }
        }
        SyncRuntime.captureRecord(
            SyncRuntime.TABLE_LIBRARY_ENTRIES, entryId,
            listOf(
                "entryId" to entryId,
                "category" to category,
                "title" to title,
                "content" to content,
                "confidence" to confidence.toString(),
                "source" to source,
                "status" to LibraryStatus.PENDING_APPROVAL,
                "createdAtMs" to now.toString()
            )
        )
        return entryId
    }

    /** The human-in-the-loop gate: promote to ACTIVE. */
    fun approveKnowledge(entryId: String) {
        setLibraryStatus(entryId, LibraryStatus.ACTIVE)
    }

    fun rejectKnowledge(entryId: String) {
        setLibraryStatus(entryId, LibraryStatus.REJECTED)
    }

    private fun setLibraryStatus(entryId: String, status: String) {
        if (entryId.isBlank()) return
        val now = System.currentTimeMillis()
        runBlocking {
            runCatching { AegisDatabase.get.agentMemoryDao().setLibraryStatus(entryId, status, now) }
        }
        SyncRuntime.captureRecord(
            SyncRuntime.TABLE_LIBRARY_ENTRIES, entryId,
            listOf("entryId" to entryId, "status" to status)
        )
    }

    /** The read-only library — ACTIVE only; agents never see the gate. */
    fun library(category: String? = null): List<LibraryEntry> = runBlocking {
        runCatching {
            val dao = AegisDatabase.get.agentMemoryDao()
            if (category.isNullOrBlank()) dao.activeLibrary() else dao.activeLibraryCategory(category)
        }.getOrDefault(emptyList())
    }

    fun pendingApprovals(limit: Int = 100): List<LibraryEntry> = runBlocking {
        runCatching { AegisDatabase.get.agentMemoryDao().libraryByStatus(LibraryStatus.PENDING_APPROVAL, limit) }
            .getOrDefault(emptyList())
    }

    /**
     * Deterministic recall — pulls ONLY the tiny relevant snippets an agent
     * needs (the cost lever). Keyword match over title/content/category,
     * ordered by confidence; episodes with lessons are appended when the query
     * matches their lesson text. Offline-first by design (repo invariant).
     */
    fun recall(query: String, limit: Int = 5): List<String> {
        if (query.isBlank()) return emptyList()
        val snippets = runBlocking {
            runCatching { AegisDatabase.get.agentMemoryDao().recall(query, limit) }.getOrDefault(emptyList())
        }
        val out = snippets.map { "[${it.category}] ${it.title}: ${it.content}" }
        if (out.size < limit) {
            lessonsLearned(limit).filter { ep ->
                query.lowercase().let { q -> ep.summary.lowercase().contains(q) || ep.lesson.lowercase().contains(q) }
            }.take(limit - out.size).forEach { ep ->
                out.add("[lesson:${ep.outcome}] ${ep.lesson.ifBlank { ep.summary }}")
            }
        }
        return out
    }

    // ── Background distillation (conflict resolution primitives) ──────────

    /**
     * Deterministic background distillation. Rules (documented in
     * MEMORY_DESIGN.md):
     *  1. Exact duplicate of an ACTIVE claim (same category + title + content)
     *     → the new PENDING copy is REJECTED (zero duplication, the original
     *     stays authoritative).
     *  2. High-confidence (≥90) PENDING claim that does NOT contradict an
     *     ACTIVE claim in the same category → auto-APPROVED (non-critical
     *     collective learning flows without a human round-trip).
     *  3. Everything else — including any conflicting claim (same category +
     *     title, different content) — STAYS PENDING for the human gate
     *     (human-in-the-loop for critical memory).
     * Returns the count of entries this run resolved. Never throws.
     */
    fun distill(): Int = runBlocking {
        runCatching {
            val dao = AegisDatabase.get.agentMemoryDao()
            var resolved = 0
            for (pending in dao.libraryByStatus(LibraryStatus.PENDING_APPROVAL, 200)) {
                val active = dao.activeLibrary().filter { it.category == pending.category && it.title == pending.title }
                val dup = active.any { it.content == pending.content }
                val conflict = active.any { it.content != pending.content }
                when {
                    dup -> {
                        dao.setLibraryStatus(pending.entryId, LibraryStatus.REJECTED, System.currentTimeMillis())
                        resolved++
                    }
                    !conflict && pending.confidence >= 90 -> {
                        dao.setLibraryStatus(pending.entryId, LibraryStatus.ACTIVE, System.currentTimeMillis())
                        resolved++
                    }
                    // conflict or low confidence → stays PENDING (human gate)
                }
            }
            resolved
        }.getOrDefault(0)
    }
}
