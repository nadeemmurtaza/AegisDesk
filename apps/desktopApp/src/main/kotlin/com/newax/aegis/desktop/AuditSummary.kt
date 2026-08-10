package com.newax.aegis.desktop

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Rolled-up statistics over the execution audit trail (Track B follow-up):
 * success rate and average duration across every recorded run. Pure — no
 * state, no IO — so it is fully testable and shared by the Audit tab and the
 * CLI `audit` command.
 *
 * An empty trail computes to 0 runs / 0% / 0 ms instead of dividing by zero —
 * the UI's empty state already tells the user the trail is empty, so the
 * summary must never be the thing that breaks.
 */
data class AuditSummary(
    val totalRuns: Int,
    val completedRuns: Int,
    val blockedRuns: Int,
    val successRatePercent: Int,
    val avgDurationMs: Long,
) {
    companion object {
        /** Computes the summary from the full trail. Input order is irrelevant. */
        fun of(entries: List<ExecutionAuditEntry>): AuditSummary {
            val total = entries.size
            if (total == 0) return AuditSummary(0, 0, 0, 0, 0L)
            val completed = entries.count { it.outcome == "COMPLETED" }
            return AuditSummary(
                totalRuns = total,
                completedRuns = completed,
                blockedRuns = total - completed,
                successRatePercent = (completed * 100.0 / total).roundToInt(),
                avgDurationMs = entries.map { it.durationMs }.average().roundToLong(),
            )
        }
    }
}
