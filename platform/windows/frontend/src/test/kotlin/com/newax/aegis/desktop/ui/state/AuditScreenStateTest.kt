package com.newax.aegis.desktop.ui.state

import com.newax.aegis.desktop.ExecutionAuditEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Track B follow-up — the audit screen state holder. All decision logic is
 * plain Kotlin against injectable seams, so ordering (newest first), the
 * empty-trail guard, and honest export outcomes are testable without touching
 * the process-wide [com.newax.aegis.desktop.ExecutionAudit] log or the disk.
 */
class AuditScreenStateTest {

    private fun entry(id: String, completedMs: Long, outcome: String = "COMPLETED") = ExecutionAuditEntry(
        goalId = id,
        goalDescription = "goal $id",
        outcome = outcome,
        reason = if (outcome == "BLOCKED") "cap not ready" else null,
        tiers = listOf("EXACT_TARGET"),
        taskCount = 2,
        startedMs = completedMs - 1_000L,
        completedMs = completedMs,
    )

    @Test
    fun `refresh shows the trail newest first`() {
        val state = AuditScreenState(auditSource = {
            listOf(entry("old", completedMs = 1_000L), entry("new", completedMs = 9_000L))
        })

        state.refresh()

        val content = state.model.value as AuditUiModel.Content
        assertEquals(listOf("new", "old"), content.entries.map { it.goalId })
    }

    @Test
    fun `refresh computes the summary over the trail`() {
        val state = AuditScreenState(auditSource = {
            listOf(
                entry("a", completedMs = 5_000L),
                entry("b", completedMs = 4_000L, outcome = "BLOCKED"),
            )
        })

        state.refresh()

        val content = state.model.value as AuditUiModel.Content
        assertEquals(2, content.summary.totalRuns)
        assertEquals(1, content.summary.completedRuns)
        assertEquals(50, content.summary.successRatePercent)
        assertEquals(1_000L, content.summary.avgDurationMs) // each fixture run is 1s long
    }

    @Test
    fun `refresh failure surfaces as an error model`() {
        val state = AuditScreenState(auditSource = { throw IllegalStateException("corrupt log") })

        state.refresh()

        val error = state.model.value as AuditUiModel.Error
        assertEquals("corrupt log", error.message)
    }

    @Test
    fun `export success reports the written path`() {
        val state = AuditScreenState(
            auditSource = { listOf(entry("g1", completedMs = 5_000L)) },
            exporter = { Result.success(Paths.get("/home/me/.aegis/audit-1.csv")) },
        )
        state.refresh()

        state.export()

        assertEquals(ExportState.Done("/home/me/.aegis/audit-1.csv"), state.exportState.value)
    }

    @Test
    fun `export failure surfaces the reason`() {
        val state = AuditScreenState(
            auditSource = { listOf(entry("g1", completedMs = 5_000L)) },
            exporter = { Result.failure(IllegalStateException("disk full")) },
        )
        state.refresh()

        state.export()

        assertEquals(ExportState.Failed("disk full"), state.exportState.value)
    }

    @Test
    fun `export of an empty trail is an honest failure, not a no-op`() {
        val state = AuditScreenState(auditSource = { emptyList() })
        state.refresh()

        state.export()

        val failed = state.exportState.value as ExportState.Failed
        assertTrue(failed.message.contains("Nothing to export"))
    }

    @Test
    fun `export before the first refresh is a no-op`() {
        val state = AuditScreenState(auditSource = { listOf(entry("g1", completedMs = 5_000L)) })

        state.export() // model is still Loading

        assertEquals(ExportState.Idle, state.exportState.value)
    }
}
