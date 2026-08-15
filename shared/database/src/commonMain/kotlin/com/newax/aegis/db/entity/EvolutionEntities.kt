package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The RLAIF-E self-learning layer (docs/AGENTS_DESIGN.md §evolution — schema
 * v19): skills are treated as **dynamic mutations**, tracked over time and
 * scored like a genetic algorithm, with a human-in-the-loop gate between any
 * learned change and the live environment.
 *
 *  [SkillEvolution] — the Evolution Ledger. One row per METHOD variant of a
 *      skill (the baseline plus every explored/fuzzed alternative). Execution
 *      telemetry (counts, latency) drives a Bayesian confidence score; the
 *      runtime exploits the best-known method or explores a variation
 *      (epsilon-greedy / UCB), and every outcome feeds back into the row.
 *      Lineage is tracked through [parentMethodId] (the genetic-algorithm
 *      mutation tree). Device-local like the agents/skills tables — this
 *      device's execution history, never synced.
 *  [StagingRecord] — the Staging Registry Database. When the system finds an
 *      error or an optimization, the candidate mutation lands in
 *      `filesDir/staging/` and a PENDING_USER_APPROVAL row is logged here.
 *      The user's Approve/Deny (the UI gate) decides whether it deploys to
 *      the active skills dir, and the decision is journaled to episodic
 *      memory (denials become failed routes). Device-local.
 *  [LearningSignal] — the RLAIF reward pipeline. Raw reinforcement events
 *      (execution errors, user corrections, handoff misalignments,
 *      benchmark observations) with a signed reward (-1..+1). Background
 *      consolidation ([com.newax.aegis.agents.LearningEngine]) turns them
 *      into ledger updates and staged mutations; [consumed] marks the
 *      reflection step done. Device-local.
 *
 * Column notes: Room keeps property names verbatim; Kotlin defaults get NO
 * DEFAULT clause unless [ColumnInfo.defaultValue] is set — the v18→v19
 * migration mirrors the generated schema exactly (same pattern as v15-v18).
 */
@Entity(
    tableName = "skill_evolution",
    indices = [Index("skillId"), Index("skillId", "methodId"), Index("status")]
)
data class SkillEvolution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The skill (or "agent:<id>" pseudo-skill for orchestration runs) this method belongs to. */
    val skillId: String,
    /** The method variant id — "baseline", "fuzz-<n>", "fix-<n>", or a user-imported name. */
    val methodId: String,
    /** Lineage (genetic-algorithm parent) — the method this one mutated from. */
    val parentMethodId: String = "",
    /** [EvolutionSource] name — EXPLOIT / EXPLORE / FUZZ. */
    val source: String = EvolutionSource.EXPLOIT,
    /** [EvolutionProtocol] name — the per-skill learning spec's loop type. */
    val protocol: String = EvolutionProtocol.DETERMINISTIC,
    val version: Int = 1,
    /** Absolute path of the method payload on disk (empty = inline in [payloadJson]). */
    val codePath: String = "",
    /** The method definition — guidance/rule text, patch body, or tool-schema variant. */
    val payloadJson: String = "{}",
    val executionCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val totalLatencyMs: Long = 0,
    val avgLatencyMs: Long = 0,
    /** The statistical weight (0..1) — Bayesian posterior mean over executions. */
    val confidence: Double = 0.5,
    /** [EvolutionStatus] name — ACTIVE / STAGED / SUPERSEDED / REJECTED. */
    val status: String = EvolutionStatus.ACTIVE,
    /** SUCCESS / FAILURE / "" — the most recent execution outcome. */
    val lastOutcome: String = "",
    /** The most recent error text (deterministic learning's mistake signal). */
    val lastError: String = "",
    @ColumnInfo(defaultValue = "0")
    val createdAtMs: Long = currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAtMs: Long = currentTimeMillis()
)

/** Staging Registry — one PENDING record per mutation awaiting the user gate. */
@Entity(
    tableName = "staging_records",
    indices = [Index("skillId"), Index("status"), Index("riskLevel")]
)
data class StagingRecord(
    @PrimaryKey val stagingId: String,
    /** The skill being mutated (or the target of a memory-rule update). */
    val skillId: String,
    /** The agent that authored the mutation ("" = the system kernel). */
    val agentId: String = "",
    val title: String,
    /** Plain-English explanation — what it does, why the agent built it. */
    val summary: String,
    /** [ChangeType] name — NEW_SKILL / MUTATION / MEMORY_RULE. */
    val changeType: String = ChangeType.MUTATION,
    /** [EvolutionProtocol] name — which learning loop produced it. */
    val protocol: String = EvolutionProtocol.DETERMINISTIC,
    /** [RiskLevel] (canonical `assistant.RiskLevel`) name — drives the urgency grouping in the Updates tab. */
    val riskLevel: String = RiskLevel.MEDIUM,
    /** The pre-mutation state (last error, current guidance) for the diff screen. */
    val diffBefore: String = "",
    /** The proposed replacement — green/red diff against [diffBefore]. */
    val diffAfter: String = "",
    /** files_to_write payload (deployment body) + method id + skill manifest for NEW_SKILL. */
    val payloadJson: String = "{}",
    /** [StagingStatus] name — the gate state machine. */
    val status: String = StagingStatus.PENDING_USER_APPROVAL,
    @ColumnInfo(defaultValue = "0")
    val createdAtMs: Long = currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val decidedAtMs: Long = 0
)

/** RLAIF reward pipeline — raw reinforcement events with signed rewards. */
@Entity(
    tableName = "learning_signals",
    indices = [Index("skillId"), Index("source"), Index("consumed")]
)
data class LearningSignal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skillId: String = "",
    val agentId: String = "",
    /** [EvolutionProtocol] name — the learning loop that emitted it. */
    val protocol: String = EvolutionProtocol.DETERMINISTIC,
    /** [LearningSignalSource] name — EXECUTION_ERROR / USER_FEEDBACK / HANDOFF_FAILURE / BENCHMARK. */
    val source: String = LearningSignalSource.EXECUTION_ERROR,
    /** Signed reward in [-1, +1] — the RLAIF magnitude. */
    val reward: Double = 0.0,
    val summary: String = "",
    val contextJson: String = "{}",
    /** False until the background reflection pass has folded it in. */
    val consumed: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val createdAtMs: Long = currentTimeMillis()
)

object EvolutionProtocol {
    /** Deterministic sandbox learning — hard execution data (exit codes, stderr). */
    const val DETERMINISTIC = "DETERMINISTIC"
    /** Critic-based semantic learning — human corrections and sentiment. */
    const val CRITIC = "CRITIC"
    /** Cross-agent learning — handoff alignments between agents. */
    const val CROSS_AGENT = "CROSS_AGENT"
}

object EvolutionSource {
    const val EXPLOIT = "EXPLOIT" // use the best known method
    const val EXPLORE = "EXPLORE" // try a variation instead
    const val FUZZ = "FUZZ"       // background fuzzer candidate
}

object EvolutionStatus {
    const val ACTIVE = "ACTIVE"           // selectable by the exploit/explore picker
    const val STAGED = "STAGED"           // awaiting the user gate
    const val SUPERSEDED = "SUPERSEDED"   // replaced by a better/approved method
    const val REJECTED = "REJECTED"       // denied at the gate (failed route)
}

object StagingStatus {
    const val PENDING_USER_APPROVAL = "PENDING_USER_APPROVAL"
    const val USER_APPROVED = "USER_APPROVED"
    const val USER_DENIED = "USER_DENIED"
    const val DEPLOYED = "DEPLOYED"
}

/**
 * Persistence codec for the `staging_records.riskLevel` column (ARCHITECTURE.md
 * concept registry, T2.2): the stored strings ARE
 * [com.newax.aegis.assistant.RiskLevel] names, and these constants derive from
 * the canonical enum so the column can never drift from the single risk
 * vocabulary. Stored values are unchanged (identical strings — no migration).
 * Delete this object once app code writes `.name` at the DAO boundary.
 */
object RiskLevel {
    val CRITICAL: String get() = com.newax.aegis.assistant.RiskLevel.CRITICAL.name // crash/broken tool fixes — top of the Updates tab
    val HIGH: String get() = com.newax.aegis.assistant.RiskLevel.HIGH.name
    val MEDIUM: String get() = com.newax.aegis.assistant.RiskLevel.MEDIUM.name
    val LOW: String get() = com.newax.aegis.assistant.RiskLevel.LOW.name           // stylistic/performance proposals — bottom
}

object ChangeType {
    const val NEW_SKILL = "NEW_SKILL"     // a brand-new capability package
    const val MUTATION = "MUTATION"       // a diff against an existing skill
    const val MEMORY_RULE = "MEMORY_RULE" // a permanent knowledge-graph update
}

object LearningSignalSource {
    const val EXECUTION_ERROR = "EXECUTION_ERROR"
    const val USER_FEEDBACK = "USER_FEEDBACK"
    const val HANDOFF_FAILURE = "HANDOFF_FAILURE"
    const val BENCHMARK = "BENCHMARK"
}
