package com.newax.aegis.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Track B follow-up — the audit summary roll-up. Pure computation: success
 * rate and average duration must round deterministically, treat only
 * COMPLETED as success, and never divide by zero on an empty trail.
 */
class AuditSummaryTest {

    private fun entry(id: String, outcome: String = "COMPLETED", durationMs: Long = 1_000L) =
        ExecutionAuditEntry(
            goalId = id,
            goalDescription = "goal $id",
            outcome = outcome,
            reason = if (outcome == "BLOCKED") "cap not ready" else null,
            tiers = listOf("EXACT_TARGET"),
            taskCount = 2,
            startedMs = 0L,
            completedMs = durationMs,
        )

    @Test
    fun `mixed trail computes success rate and average duration`() {
        val summary = AuditSummary.of(
            listOf(
                entry("a", durationMs = 1_000L),
                entry("b", durationMs = 2_000L),
                entry("c", outcome = "BLOCKED", durationMs = 500L),
            )
        )

        assertEquals(3, summary.totalRuns)
        assertEquals(2, summary.completedRuns)
        assertEquals(1, summary.blockedRuns)
        assertEquals(67, summary.successRatePercent) // 66.67 → 67
        assertEquals(1_167L, summary.avgDurationMs) // (1000 + 2000 + 500) / 3 = 1166.67 → 1167
    }

    @Test
    fun `every blocked run is a zero success rate`() {
        val summary = AuditSummary.of(
            listOf(entry("a", outcome = "BLOCKED"), entry("b", outcome = "BLOCKED"))
        )

        assertEquals(2, summary.totalRuns)
        assertEquals(0, summary.completedRuns)
        assertEquals(2, summary.blockedRuns)
        assertEquals(0, summary.successRatePercent)
    }

    @Test
    fun `every completed run is a hundred percent`() {
        val summary = AuditSummary.of(listOf(entry("a"), entry("b"), entry("c")))

        assertEquals(3, summary.completedRuns)
        assertEquals(100, summary.successRatePercent)
    }

    @Test
    fun `empty trail computes zeros instead of dividing by zero`() {
        assertEquals(AuditSummary(0, 0, 0, 0, 0L), AuditSummary.of(emptyList()))
    }

    @Test
    fun `average duration rounds to the nearest millisecond`() {
        val summary = AuditSummary.of(listOf(entry("a", durationMs = 100L), entry("b", durationMs = 200L)))

        assertEquals(150L, summary.avgDurationMs)
    }
}
