package com.newax.aegis.desktop

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One launch tier's slice of the trail: how many runs used it and how many of
 * those completed. A run with several tiers (a task ladder that walked
 * EXACT_TARGET then WIN32_AUTOMATION) counts once per tier it touched.
 */
data class TierBreakdown(
    val tier: String,
    val runs: Int,
    val completed: Int,
    val successRatePercent: Int,
)

/**
 * Rolled-up statistics over the execution audit trail (Track B follow-up):
 * success rate, average duration, and a per-launch-tier breakdown across every
 * recorded run. Pure — no state, no IO — so it is fully testable and shared by
 * the Audit tab and the CLI `audit` command.
 *
 * An empty trail computes to 0 runs / 0% / 0 ms instead of dividing by zero —
 * the UI's empty state already tells the user the trail is empty, so the
 * summary must never be the thing that breaks. Runs recorded without any tier
 * (empty `tiers`) appear in the totals but not in the tier breakdown.
 */
data class AuditSummary(
    val totalRuns: Int,
    val completedRuns: Int,
    val blockedRuns: Int,
    val successRatePercent: Int,
    val avgDurationMs: Long,
    val tierBreakdown: List<TierBreakdown> = emptyList(),
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
                tierBreakdown = tierBreakdownOf(entries),
            )
        }

        /**
         * Runs-per-tier with per-tier success: most-used tier first, name as the
         * deterministic tie-break. A multi-tier run contributes to every tier it
         * touched; runs with no tier are omitted (they're still in the totals).
         */
        private fun tierBreakdownOf(entries: List<ExecutionAuditEntry>): List<TierBreakdown> =
            entries
                .flatMap { entry -> entry.tiers.map { tier -> tier to entry } }
                .groupBy({ it.first }, { it.second })
                .map { (tier, tierEntries) ->
                    val completed = tierEntries.count { it.outcome == "COMPLETED" }
                    TierBreakdown(
                        tier = tier,
                        runs = tierEntries.size,
                        completed = completed,
                        successRatePercent = (completed * 100.0 / tierEntries.size).roundToInt(),
                    )
                }
                .sortedWith(compareByDescending<TierBreakdown> { it.runs }.thenBy { it.tier })
    }
}
