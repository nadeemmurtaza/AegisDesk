package com.newax.aegis.memory

import com.newax.aegis.SyncRuntime
import com.newax.aegis.db.NewaxDatabase
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
                val db = NewaxDatabase.get
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
            NewaxDatabase.get.agentMemoryDao().scratchpadFor(agentId)
                .firstOrNull { it.key == key }?.value
        }.getOrNull()
    }

    fun scratchpadFor(agentId: String): List<AgentScratchpad> = runBlocking {
        runCatching {
            val dao = NewaxDatabase.get.agentMemoryDao()
            dao.pruneExpiredScratchpad(System.currentTimeMillis())
            dao.scratchpadFor(agentId)
        }.getOrDefault(emptyList())
    }

    fun scratchpadDelete(agentId: String, key: String) {
        runBlocking { runCatching { NewaxDatabase.get.agentMemoryDao().deleteScratchpad(agentId, key) } }
    }

    fun scratchpadClear(agentId: String) {
        runBlocking { runCatching { NewaxDatabase.get.agentMemoryDao().clearScratchpad(agentId) } }
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
                NewaxDatabase.get.agentMemoryDao().insertEpisode(
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
        // Semantic path — best-effort: no-op when the embedder isn't ready.
        runCatching {
            com.newax.aegis.engine.embedding.VectorStore.indexEpisode(
                NewaxDatabase.get, episodeId, summary, lesson, outcome
            )
        }
        return episodeId
    }

    /** The chronological timeline — newest first. */
    fun recentEpisodes(limit: Int = 50): List<Episode> = runBlocking {
        runCatching { NewaxDatabase.get.agentMemoryDao().recentEpisodes(limit) }.getOrDefault(emptyList())
    }

    /** FAILURE episodes with lessons — the collective-learning feed. */
    fun lessonsLearned(limit: Int = 50): List<Episode> = runBlocking {
        runCatching { NewaxDatabase.get.agentMemoryDao().lessonsLearned(limit) }.getOrDefault(emptyList())
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
                NewaxDatabase.get.agentMemoryDao().insertHandoff(
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
            runCatching { NewaxDatabase.get.agentMemoryDao().updateHandoffStatus(handoffId, HandoffStatus.ACKED) }
        }
        SyncRuntime.captureRecord(
            SyncRuntime.TABLE_HANDOFFS, handoffId,
            listOf("handoffId" to handoffId, "status" to HandoffStatus.ACKED)
        )
    }

    fun handoffInbox(agent: String): List<HandoffEntry> = runBlocking {
        runCatching { NewaxDatabase.get.agentMemoryDao().handoffInbox(agent) }.getOrDefault(emptyList())
    }

    fun handoffOutbox(agent: String, limit: Int = 50): List<HandoffEntry> = runBlocking {
        runCatching { NewaxDatabase.get.agentMemoryDao().handoffOutbox(agent, limit) }.getOrDefault(emptyList())
    }

    // ── Work log (zero work duplication, device-local) ─────────────────────

    fun isWorkDone(action: String, resource: String): Boolean = runBlocking {
        runCatching {
            NewaxDatabase.get.agentMemoryDao().workFor(action, resource)?.status == WorkLogStatus.DONE
        }.getOrDefault(false)
    }

    /** Claim a (action, resource) so no other agent redoes it. */
    fun claimWork(action: String, resource: String, agentId: String): Boolean {
        if (action.isBlank() || resource.isBlank()) return false
        val inserted = runBlocking {
            runCatching {
                NewaxDatabase.get.agentMemoryDao().insertWork(
                    WorkLogEntry(action = action, resource = resource, agentId = agentId, status = WorkLogStatus.IN_PROGRESS)
                )
            }.getOrDefault(0L)
        }
        return inserted > 0
    }

    fun completeWork(action: String, resource: String) {
        runBlocking {
            runCatching {
                NewaxDatabase.get.agentMemoryDao().updateWork(action, resource, WorkLogStatus.DONE, System.currentTimeMillis())
            }
        }
    }

    fun recentWork(limit: Int = 50): List<WorkLogEntry> = runBlocking {
        runCatching { NewaxDatabase.get.agentMemoryDao().recentWork(limit) }.getOrDefault(emptyList())
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
                NewaxDatabase.get.agentMemoryDao().upsertLibrary(
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

    /** The human-in-the-loop gate: promote to ACTIVE (and into the vector index). */
    fun approveKnowledge(entryId: String) {
        setLibraryStatus(entryId, LibraryStatus.ACTIVE)
        runCatching {
            val db = NewaxDatabase.get
            val entry = runBlocking { db.agentMemoryDao().libraryById(entryId) } ?: return
            com.newax.aegis.engine.embedding.VectorStore.indexLibrary(db, entryId, entry.category, entry.title, entry.content)
        }
    }

    fun rejectKnowledge(entryId: String) {
        setLibraryStatus(entryId, LibraryStatus.REJECTED)
        runCatching {
            com.newax.aegis.engine.embedding.VectorStore.removeLibrary(NewaxDatabase.get, entryId)
        }
    }

    private fun setLibraryStatus(entryId: String, status: String) {
        if (entryId.isBlank()) return
        val now = System.currentTimeMillis()
        runBlocking {
            runCatching { NewaxDatabase.get.agentMemoryDao().setLibraryStatus(entryId, status, now) }
        }
        SyncRuntime.captureRecord(
            SyncRuntime.TABLE_LIBRARY_ENTRIES, entryId,
            listOf("entryId" to entryId, "status" to status)
        )
    }

    /** The read-only library — ACTIVE only; agents never see the gate. */
    fun library(category: String? = null): List<LibraryEntry> = runBlocking {
        runCatching {
            val dao = NewaxDatabase.get.agentMemoryDao()
            if (category.isNullOrBlank()) dao.activeLibrary() else dao.activeLibraryCategory(category)
        }.getOrDefault(emptyList())
    }

    fun pendingApprovals(limit: Int = 100): List<LibraryEntry> = runBlocking {
        runCatching { NewaxDatabase.get.agentMemoryDao().libraryByStatus(LibraryStatus.PENDING_APPROVAL, limit) }
            .getOrDefault(emptyList())
    }

    /**
     * Deterministic recall — pulls ONLY the tiny relevant snippets an agent
     * needs (the cost lever). Keyword match over title/content/category,
     * ordered by confidence; episodes with lessons are appended when the query
     * matches their lesson text. Offline-first by design (repo invariant).
     */
    /**
     * Unified recall over the three synced layers — the snippet the assistant
     * actually consumes (via [com.newax.aegis.engine.embedding.VectorMemorySearch]).
     * Merge order, deduped:
     *   1. semantic vector results (indexed ACTIVE library + episodes + the
     *      repo's existing fact/memory/triple/edge embeddings),
     *   2. keyword matches over ACTIVE library entries,
     *   3. matching lessons from FAILURE episodes.
     * The vector leg is best-effort (graceful degradation when the embedder
     * isn't ready — keyword + lessons still answer).
     */
    fun recall(query: String, limit: Int = 5): List<String> {
        if (query.isBlank()) return emptyList()
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        val seen = LinkedHashSet<String>(limit * 3)
        val out = mutableListOf<String>()
        fun add(text: String) {
            val key = text.take(140)
            if (seen.add(key) && out.size < limit) out.add(text)
        }

        // 1. Semantic vector recall over everything indexed (incl. the agent layers).
        runCatching {
            com.newax.aegis.engine.embedding.VectorStore.search(db, query, limit).forEach { add(it.text) }
        }

        // 2. Keyword recall over the ACTIVE library (deterministic, offline-first),
        //    recency-boosted: claims confirmed in the last month rank above
        //    stale ones at equal confidence (forgetting is a ranking signal).
        runBlocking {
            runCatching { db.agentMemoryDao().recall(query, limit * 3) }.getOrDefault(emptyList())
        }.sortedByDescending { entry ->
            val ageAnchor = if (entry.decidedAtMs > 0) entry.decidedAtMs else entry.createdAtMs
            entry.confidence + if (System.currentTimeMillis() - ageAnchor < RECENT_WINDOW_MS) RECENT_BONUS else 0
        }.take(limit).forEach { add("[${it.category}] ${it.title}: ${it.content}") }

        // 3. Lessons from FAILURE episodes matching the query.
        val q = query.lowercase()
        lessonsLearned(limit).filter { ep ->
            ep.summary.lowercase().contains(q) || ep.lesson.lowercase().contains(q)
        }.forEach { ep -> add("[lesson:${ep.outcome}] ${ep.lesson.ifBlank { ep.summary }}") }

        return out.take(limit)
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
     * Runs the full background pass: conflict resolution + episodic→semantic
     * consolidation ([consolidateLessons]) + forgetting/decay ([decay]).
     * Returns the number of entries this run resolved. Never throws.
     */
    fun distill(): Int {
        return resolveConflicts() + consolidateLessons() + decay()
    }

    private fun resolveConflicts(): Int = runBlocking {
        runCatching {
            val dao = NewaxDatabase.get.agentMemoryDao()
            var resolved = 0
            for (pending in dao.libraryByStatus(LibraryStatus.PENDING_APPROVAL, 200)) {
                val active = dao.activeLibrary().filter { it.category == pending.category && it.title == pending.title }
                val dup = active.any { it.content == pending.content }
                val conflict = active.any { it.content != pending.content }
                when {
                    dup -> {
                        dao.setLibraryStatus(pending.entryId, LibraryStatus.REJECTED, System.currentTimeMillis())
                        com.newax.aegis.engine.embedding.VectorStore.removeLibrary(NewaxDatabase.get, pending.entryId)
                        resolved++
                    }
                    !conflict && pending.confidence >= 90 -> {
                        dao.setLibraryStatus(pending.entryId, LibraryStatus.ACTIVE, System.currentTimeMillis())
                        com.newax.aegis.engine.embedding.VectorStore.indexLibrary(
                            NewaxDatabase.get, pending.entryId, pending.category, pending.title, pending.content
                        )
                        resolved++
                    }
                    // conflict or low confidence → stays PENDING (human gate)
                }
            }
            resolved
        }.getOrDefault(0)
    }

    /**
     * Episodic → semantic consolidation (the "sleep" step): a lesson repeated
     * across ≥ 2 FAILURE episodes is promoted to an ACTIVE library entry
     * (category `learned`, source `consolidation`, confidence 90) — the fix
     * becomes semantic knowledge every agent inherits. Skips lessons already
     * covered by an ACTIVE entry (zero duplication). Journaled so the
     * promotion propagates. Returns the number promoted.
     */
    fun consolidateLessons(): Int = runBlocking {
        runCatching {
            val db = NewaxDatabase.get
            val dao = db.agentMemoryDao()
            val lessons = dao.allLessonTexts().map { it.trim().lowercase() }.filter { it.isNotBlank() }
            if (lessons.isEmpty()) return@runCatching 0
            val repeated = lessons.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
            if (repeated.isEmpty()) return@runCatching 0
            val activeContent = dao.activeLibrary().map { it.content.trim().lowercase() }.toSet()
            var promoted = 0
            repeated.forEach { lesson ->
                if (lesson in activeContent) return@forEach
                val entryId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                dao.upsertLibrary(
                    LibraryEntry(
                        entryId = entryId,
                        category = "learned",
                        title = lesson.take(64),
                        content = lesson,
                        confidence = 90,
                        source = "consolidation",
                        status = LibraryStatus.ACTIVE,
                        createdAtMs = now,
                        decidedAtMs = now
                    )
                )
                com.newax.aegis.engine.embedding.VectorStore.indexLibrary(db, entryId, "learned", lesson.take(64), lesson)
                SyncRuntime.captureRecord(
                    SyncRuntime.TABLE_LIBRARY_ENTRIES, entryId,
                    listOf(
                        "entryId" to entryId,
                        "category" to "learned",
                        "title" to lesson.take(64),
                        "content" to lesson,
                        "confidence" to "90",
                        "source" to "consolidation",
                        "status" to LibraryStatus.ACTIVE,
                        "createdAtMs" to now.toString()
                    )
                )
                promoted++
            }
            promoted
        }.getOrDefault(0)
    }

    /**
     * Forgetting/decay for semantic memory (docs/MEMORY_DESIGN.md): ACTIVE
     * library entries older than [DECAY_AFTER_MS] with no re-confirmation lose
     * 10 confidence per pass; once below [FORGET_BELOW], the claim is REJECTED
     * (forgotten) and dropped from the vector index. Journaled so the decay
     * propagates. Episodic memory deliberately never decays — chronological
     * records are meant to persist. Returns the number of entries touched.
     */
    fun decay(): Int = runBlocking {
        runCatching {
            val db = NewaxDatabase.get
            val dao = db.agentMemoryDao()
            val now = System.currentTimeMillis()
            var touched = 0
            for (entry in dao.activeLibrary()) {
                val ageAnchor = if (entry.decidedAtMs > 0) entry.decidedAtMs else entry.createdAtMs
                if (now - ageAnchor < DECAY_AFTER_MS) continue
                val next = entry.confidence - DECAY_STEP
                if (next < FORGET_BELOW) {
                    dao.setLibraryStatus(entry.entryId, LibraryStatus.REJECTED, now)
                    com.newax.aegis.engine.embedding.VectorStore.removeLibrary(db, entry.entryId)
                    SyncRuntime.captureRecord(
                        SyncRuntime.TABLE_LIBRARY_ENTRIES, entry.entryId,
                        listOf("entryId" to entry.entryId, "status" to LibraryStatus.REJECTED)
                    )
                } else {
                    dao.updateLibraryConfidence(entry.entryId, next)
                    SyncRuntime.captureRecord(
                        SyncRuntime.TABLE_LIBRARY_ENTRIES, entry.entryId,
                        listOf("entryId" to entry.entryId, "status" to LibraryStatus.ACTIVE, "confidence" to next.toString())
                    )
                }
                touched++
            }
            touched
        }.getOrDefault(0)
    }

    private const val DECAY_AFTER_MS = 90L * 24 * 60 * 60 * 1000L
    private const val DECAY_STEP = 10
    private const val FORGET_BELOW = 40
    private const val RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000L
    private const val RECENT_BONUS = 20
}
