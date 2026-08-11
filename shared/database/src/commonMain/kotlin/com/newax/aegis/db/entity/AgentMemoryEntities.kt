package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The three-layer hierarchical agent memory (docs/MEMORY_DESIGN.md):
 *
 *  L1 Global "Library"  — [LibraryEntry]: shared, agent read-only. Curated
 *      knowledge (goals, preferences, project docs, learned fixes). Writes go
 *      through the [LibraryStatus.PENDING_APPROVAL] gate (human-in-the-loop)
 *      before promotion to ACTIVE. Syncs through the mesh (collective learning).
 *  L2 Agent "Scratchpad" — [AgentScratchpad]: private and isolated per agent.
 *      Working state, TTL-scoped. NEVER syncs — isolation is the point.
 *  L3 "Handoff" state  — [HandoffEntry]: shared-write structured artifacts
 *      with pointers (refId). Agent A finishes a sub-task, writes a clean
 *      summary artifact, and passes a pointer to Agent B. Syncs (the mesh
 *      relays it store-and-forward).
 *
 * Supporting layers:
 *  [Episode] — episodic memory (the "periodic" layer): chronological records
 *      with outcome + lesson, so agents learn from mistakes and keep temporal
 *      awareness. Syncs.
 *  [WorkLogEntry] — zero-work-duplication: a (action, resource) claim that has
 *      been done. Local to the device (the swarm shares one DB), never synced.
 */

/** L2 — private per-agent working memory. Local-only, never syncs. */
@Entity(
    tableName = "agent_scratchpad",
    primaryKeys = ["agentId", "key"],
    indices = [Index("agentId")]
)
data class AgentScratchpad(
    val agentId: String,
    val key: String,
    val value: String,
    @ColumnInfo(defaultValue = "0")
    val updatedAtMs: Long = currentTimeMillis(),
    /** 0 = no expiry; otherwise the scratchpad entry is pruned at this wall time. */
    @ColumnInfo(defaultValue = "0")
    val expiresAtMs: Long = 0
)

/** Episodic memory — chronological records with outcome + lesson. Syncs. */
@Entity(
    tableName = "episodes",
    indices = [Index("agentId"), Index("occurredAtMs"), Index("outcome")]
)
data class Episode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: String,
    val agentId: String,
    val category: String,
    val summary: String,
    /** [EpisodeOutcome] name (SUCCESS / FAILURE / OBSERVATION). */
    val outcome: String,
    /** The distilled lesson — what to do differently next time (empty for plain observations). */
    val lesson: String = "",
    val occurredAtMs: Long = currentTimeMillis(),
    /** Free-form ref (conversation id, task id, graph node, ...) for temporal threading. */
    val contextRef: String = "",
    // ── sync metadata: LWW per episodeId ──
    @ColumnInfo(defaultValue = "0")
    val syncHcWall: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val syncHcCounter: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val syncDeviceId: String = "",
    @ColumnInfo(defaultValue = "0")
    val syncTombstone: Boolean = false
)

object EpisodeOutcome {
    const val SUCCESS = "SUCCESS"
    const val FAILURE = "FAILURE"
    const val OBSERVATION = "OBSERVATION"
}

/** L3 — shared-write handoff artifacts with clean pointers. Syncs. */
@Entity(
    tableName = "handoffs",
    indices = [Index("fromAgent"), Index("toAgent"), Index("status"), Index("createdAtMs")]
)
data class HandoffEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val handoffId: String,
    val fromAgent: String,
    val toAgent: String,
    val task: String,
    /** The clean summary artifact the consumer reads — not raw scratchpad. */
    val summary: String,
    /** Structured payload (JSON) — the artifact body. */
    val artifactJson: String = "{}",
    /** [HandoffStatus] name. */
    val status: String = HandoffStatus.PENDING,
    /** The pointer passed to the consumer (artifact id / ref). */
    val refId: String = "",
    val createdAtMs: Long = currentTimeMillis(),
    // ── sync metadata: LWW per handoffId ──
    @ColumnInfo(defaultValue = "0")
    val syncHcWall: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val syncHcCounter: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val syncDeviceId: String = "",
    @ColumnInfo(defaultValue = "0")
    val syncTombstone: Boolean = false
)

object HandoffStatus {
    const val PENDING = "PENDING"
    const val ACKED = "ACKED"
    const val EXPIRED = "EXPIRED"
}

/** Zero-work-duplication log — one (action, resource) done once. Local-only. */
@Entity(
    tableName = "work_log",
    indices = [Index("action"), Index("resource"), Index("status")]
)
data class WorkLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val action: String,
    val resource: String,
    val agentId: String,
    /** [WorkLogStatus] name. */
    val status: String = WorkLogStatus.DONE,
    @ColumnInfo(defaultValue = "0")
    val atMs: Long = currentTimeMillis()
) {
    /** Unique (action, resource) — the dedupe key. */
    val dedupeKey: String get() = action + "\u0001" + resource
}

object WorkLogStatus {
    const val DONE = "DONE"
    const val IN_PROGRESS = "IN_PROGRESS"
}

/** L1 — the shared read-only library, behind the human-in-the-loop gate. Syncs. */
@Entity(
    tableName = "library_entries",
    indices = [Index("category"), Index("status"), Index("title")]
)
data class LibraryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: String,
    val category: String,
    val title: String,
    val content: String,
    @ColumnInfo(defaultValue = "80")
    val confidence: Int = 80,
    /** Where the claim came from (agent, import, user note). */
    val source: String = "",
    /** [LibraryStatus] name — PENDING_APPROVAL is the gate. */
    val status: String = LibraryStatus.PENDING_APPROVAL,
    @ColumnInfo(defaultValue = "0")
    val createdAtMs: Long = currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val decidedAtMs: Long = 0,
    // ── sync metadata: LWW per entryId ──
    @ColumnInfo(defaultValue = "0")
    val syncHcWall: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val syncHcCounter: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val syncDeviceId: String = "",
    @ColumnInfo(defaultValue = "0")
    val syncTombstone: Boolean = false
)

object LibraryStatus {
    /** Waiting for the human validation gate — not visible to agents. */
    const val PENDING_APPROVAL = "PENDING_APPROVAL"
    /** Approved — read-only for agents; surfaced by recall()/library(). */
    const val ACTIVE = "ACTIVE"
    /** Rejected at the gate — kept for audit, never surfaced. */
    const val REJECTED = "REJECTED"
}
