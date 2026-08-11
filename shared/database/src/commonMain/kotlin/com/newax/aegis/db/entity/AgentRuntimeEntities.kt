package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The agent runtime layer (docs/AGENTS_DESIGN.md §runtime — the PRAM
 * interface): every agent exposes the SAME controller contract
 * (run / abort / get_status / health_check) and communicates through strict
 * structured blocks, not raw chatter.
 *
 *  [AgentSessionEntity] — the run ledger. One row per task execution with a
 *      live [phase] ("Planning" / "Thinking" / "Running Tool" / "Done"), the
 *      strict result block ({"status":"success","artifact_path":…,"summary":…})
 *      or error block ({"status":"error","error_type":…,"message":…}), and a
 *      [frozenPath] when the session was serialized to disk (freeze/thaw —
 *      skill.sys.serialize_state). Device-local: a session is a record of
 *      what THIS device's copy of the agent did — never synced.
 *  [AgentHealthEntity] — the health-audit ledger (skill.sys.health_audit).
 *      One row per agent with the latest audit outcome. A FAULTED agent is
 *      quarantined (auto-disabled) until a human restores it; the fault count
 *      and the action taken are recorded here for the audit trail.
 */
@Entity(
    tableName = "agent_sessions",
    indices = [Index("agentId"), Index("status")]
)
data class AgentSessionEntity(
    @PrimaryKey val sessionId: String,
    val agentId: String,
    /** [SessionStatus] name — RUNNING / COMPLETED / ABORTED / FAILED / FROZEN. */
    val status: String,
    /** [SessionPhase] name — the live runtime metric surfaced by get_status(). */
    val phase: String,
    val taskPrompt: String,
    /** Free-form context JSON (plan summary, memory pointers, skills in scope). */
    val contextJson: String = "{}",
    /** The strict success block: {"status":"success","artifact_path":…,"summary":…}. */
    val resultJson: String = "",
    /** The strict error block: {"status":"error","error_type":…,"message":…}. */
    val errorJson: String = "",
    /** Absolute path of the frozen payload when status == FROZEN. */
    val frozenPath: String = "",
    @ColumnInfo(defaultValue = "0")
    val startedAtMs: Long = currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAtMs: Long = currentTimeMillis()
)

object SessionStatus {
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val ABORTED = "ABORTED"
    const val FAILED = "FAILED"
    const val FROZEN = "FROZEN"
}

/** The live runtime metric get_status() reports — what the agent is doing NOW. */
object SessionPhase {
    const val PLANNING = "Planning"
    const val THINKING = "Thinking"
    const val RUNNING_TOOL = "Running Tool"
    const val RESTORED = "Restored"
    const val DONE = "Done"
}

/** Uniform error_type values in the strict error block (the UI reads these). */
object AgentErrorType {
    const val PERMISSION_DENIAL = "PERMISSION_DENIAL"
    const val MODEL_ERROR = "MODEL_ERROR"
    const val INTERNAL_FAULT = "INTERNAL_FAULT"
    const val USER_ABORT = "USER_ABORT"
}

@Entity(tableName = "agent_health")
data class AgentHealthEntity(
    @PrimaryKey val agentId: String,
    /** [AgentHealthStatus] name — HEALTHY / DEGRADED / FAULTED. */
    val status: String,
    /** Comma-separated audit findings (database-unreachable, package-missing, …). */
    val detail: String = "",
    /** Cumulative fault count — every FAULTED audit increments it. */
    val faultCount: Int = 0,
    /** What the runtime did about it (e.g. "auto-disabled (quarantine)"). */
    val actionTaken: String = "",
    @ColumnInfo(defaultValue = "0")
    val lastCheckAtMs: Long = currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val lastRecoveredAtMs: Long = 0
)

object AgentHealthStatus {
    const val HEALTHY = "HEALTHY"
    const val DEGRADED = "DEGRADED"
    const val FAULTED = "FAULTED"
}
