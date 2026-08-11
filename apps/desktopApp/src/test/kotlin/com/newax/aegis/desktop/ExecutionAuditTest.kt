package com.newax.aegis.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase B3 — the execution audit log. Pure JVM: append, newest-first ordering,
 * cap, and snapshot restore are all deterministic in-process.
 */
class ExecutionAuditTest {

    private fun entry(id: String, outcome: String = "COMPLETED") = ExecutionAuditEntry(
        goalId = id,
        goalDescription = "goal $id",
        outcome = outcome,
        reason = if (outcome == "BLOCKED") "cap not ready" else null,
        tiers = listOf("EXACT_TARGET"),
        taskCount = 2,
        startedMs = 1_000L,
        completedMs = 2_000L,
    )

    @Test
    fun `record appends and recent returns newest first`() {
        ExecutionAudit.replaceAll(emptyList())

        ExecutionAudit.record(entry("a"))
        ExecutionAudit.record(entry("b"))
        ExecutionAudit.record(entry("c"))

        assertEquals(listOf("c", "b", "a"), ExecutionAudit.recent().map { it.goalId })
    }

    @Test
    fun `recent respects the limit`() {
        ExecutionAudit.replaceAll(emptyList())

        repeat(5) { i -> ExecutionAudit.record(entry("g$i")) }

        val recent = ExecutionAudit.recent(3)
        assertEquals(3, recent.size)
        assertEquals("g4", recent.first().goalId)
        assertEquals(listOf("g4", "g3", "g2"), recent.map { it.goalId })
    }

    @Test
    fun `replaceAll restores persisted entries on bootstrap`() {
        ExecutionAudit.replaceAll(listOf(entry("x"), entry("y")))

        assertEquals(listOf("x", "y"), ExecutionAudit.all().map { it.goalId })
        assertEquals(listOf("y", "x"), ExecutionAudit.recent().map { it.goalId })
    }

    @Test
    fun `blocked entries carry their reason`() {
        ExecutionAudit.replaceAll(emptyList())

        ExecutionAudit.record(entry("g1", outcome = "BLOCKED"))

        val entry = ExecutionAudit.recent().single()
        assertEquals("BLOCKED", entry.outcome)
        assertEquals("cap not ready", entry.reason)
    }
}
