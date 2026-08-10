package com.newax.aegis.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Track B follow-up — the audit summary roll-up. Pure computation: success
 * rate and average duration must round deterministically, treat only
 * COMPLETED as success, and never divide by zero on an empty trail.
 */
class AuditSummaryTest {

    private fun entry(
        id: String,
        outcome: String = "COMPLETED",
        durationMs: Long = 1_000L,
        tiers: List<String> = listOf("EXACT_TARGET"),
    ) = ExecutionAuditEntry(
        goalId = id,
        goalDescription = "goal $id",
        outcome = outcome,
        reason = if (outcome == "BLOCKED") "cap not ready" else null,
        tiers = tiers,
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
    fun `tier breakdown counts runs and success per tier`() {
        val summary = AuditSummary.of(
            listOf(
                entry("a", tiers = listOf("EXACT_TARGET")),
                entry("b", tiers = listOf("EXACT_TARGET")),
                entry("c", outcome = "BLOCKED", tiers = listOf("EXACT_TARGET", "PROCESS_LAUNCH")),
            )
        )

        // Most-used tier first (EXACT_TARGET 3 runs, PROCESS_LAUNCH 1 run).
        assertEquals(listOf("EXACT_TARGET", "PROCESS_LAUNCH"), summary.tierBreakdown.map { it.tier })
        assertEquals(TierBreakdown("EXACT_TARGET", runs = 3, completed = 2, successRatePercent = 67), summary.tierBreakdown[0])
        assertEquals(TierBreakdown("PROCESS_LAUNCH", runs = 1, completed = 0, successRatePercent = 0), summary.tierBreakdown[1])
    }

    @Test
    fun `multi-tier runs count once per tier they touched`() {
        val summary = AuditSummary.of(
            listOf(
                entry("a", outcome = "BLOCKED", tiers = listOf("EXACT_TARGET", "WIN32_AUTOMATION")),
                entry("b", tiers = listOf("WIN32_AUTOMATION")),
            )
        )

        // WIN32_AUTOMATION touched by both runs (1 completed), EXACT_TARGET by one.
        assertEquals(listOf("WIN32_AUTOMATION", "EXACT_TARGET"), summary.tierBreakdown.map { it.tier })
        assertEquals(TierBreakdown("WIN32_AUTOMATION", runs = 2, completed = 1, successRatePercent = 50), summary.tierBreakdown[0])
        assertEquals(TierBreakdown("EXACT_TARGET", runs = 1, completed = 0, successRatePercent = 0), summary.tierBreakdown[1])
    }

    @Test
    fun `runs without tiers stay in the totals but not in the breakdown`() {
        val summary = AuditSummary.of(listOf(entry("a", tiers = emptyList())))

        assertEquals(1, summary.totalRuns)
        assertTrue(summary.tierBreakdown.isEmpty())
    }

    @Test
    fun `average duration rounds to the nearest millisecond`() {
        val summary = AuditSummary.of(listOf(entry("a", durationMs = 100L), entry("b", durationMs = 200L)))

        assertEquals(150L, summary.avgDurationMs)
    }
}
