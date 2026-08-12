package com.newax.aegis.desktop.ui.state

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.authority.PolicyEngine
import com.newax.aegis.authority.PolicyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

/**
 * Policy tab state holder — the plain-Kotlin core of the desktop authority
 * surface. Every decision (row derivation, filter semantics, summary following
 * the filter, approval-pressure breakdown, mutation routing) is testable
 * against an in-memory engine and a scripted history, without the process-wide
 * holder or the disk.
 */
class PolicyScreenStateTest {

    private fun record(
        actionClass: String,
        decision: PolicyDecision,
        atMs: Long = System.currentTimeMillis(),
    ): PolicyAuditRecord = PolicyAuditRecord(
        actionClass = actionClass,
        actionSummary = "test",
        origin = ActionOrigin.AGENT,
        risk = RiskLevel.MEDIUM,
        mode = PolicyMode.APPROVAL,
        decision = decision,
        reason = "test",
        auditedAtMs = atMs,
    )

    @Test
    fun `rows surface the curated classes plus any history-only classes`() {
        val state = PolicyScreenState(
            engineSource = { PolicyEngine() },
            historySource = {
                listOf(record("Send", PolicyDecision.REQUIRE_APPROVAL), record("MysteryClass", PolicyDecision.DENY))
            },
        )

        state.refresh()

        val content = state.model.value as PolicyUiModel.Content
        val classes = content.rows.map { it.actionClass }
        assertTrue("curated OpenApp present", "OpenApp" in classes)
        assertTrue("curated Send present", "Send" in classes)
        assertTrue("history-only class appended", "MysteryClass" in classes)
    }

    @Test
    fun `filter slices the trail and the summary follows the filter`() {
        val now = System.currentTimeMillis()
        val state = PolicyScreenState(
            engineSource = { PolicyEngine() },
            historySource = {
                listOf(
                    record("Send", PolicyDecision.REQUIRE_APPROVAL, atMs = now),
                    record("OpenApp", PolicyDecision.AUTO_EXECUTE, atMs = now),
                )
            },
        )
        state.refresh()
        assertEquals(2, (state.model.value as PolicyUiModel.Content).records.size)

        state.setDecisionFilter(PolicyDecision.REQUIRE_APPROVAL)

        val content = state.model.value as PolicyUiModel.Content
        assertEquals(1, content.filteredRecords.size)
        assertEquals("Send", content.filteredRecords.first().actionClass)
        assertEquals("summary follows the filter", 1, content.summary.approvals)
        assertEquals(0, content.summary.autoExecuted)
    }

    @Test
    fun `clearing the filter restores the full trail`() {
        val now = System.currentTimeMillis()
        val state = PolicyScreenState(
            engineSource = { PolicyEngine() },
            historySource = {
                listOf(
                    record("Send", PolicyDecision.REQUIRE_APPROVAL, atMs = now),
                    record("OpenApp", PolicyDecision.DENY, atMs = now),
                )
            },
        )
        state.refresh()
        state.setDecisionFilter(PolicyDecision.DENY)
        assertEquals(1, (state.model.value as PolicyUiModel.Content).filteredRecords.size)

        state.setDecisionFilter(null)

        assertEquals(2, (state.model.value as PolicyUiModel.Content).filteredRecords.size)
    }

    @Test
    fun `setMode and setDenied route through the one engine`() {
        val engine = PolicyEngine()
        val state = PolicyScreenState(engineSource = { engine }, historySource = { emptyList() })
        state.refresh()

        state.setMode("Send", PolicyMode.STRONG_CONFIRMATION)
        state.setDenied("DeleteFile", true)

        assertEquals(PolicyMode.STRONG_CONFIRMATION, engine.modeOverride("Send"))
        assertTrue(engine.isDenied("DeleteFile"))
        val content = state.model.value as PolicyUiModel.Content
        assertTrue(content.rows.first { it.actionClass == "Send" }.custom)
        assertTrue(content.rows.first { it.actionClass == "DeleteFile" }.denied)
    }

    @Test
    fun `reset clears override and deny back to the risk-based default`() {
        val engine = PolicyEngine()
        engine.setModeOverride("Send", PolicyMode.APPROVAL)
        engine.setDenied("Send", true)
        val state = PolicyScreenState(engineSource = { engine }, historySource = { emptyList() })
        state.refresh()

        state.reset("Send")

        assertFalse(engine.isDenied("Send"))
        assertNull(engine.modeOverride("Send"))
        val row = (state.model.value as PolicyUiModel.Content).rows.first { it.actionClass == "Send" }
        assertFalse(row.custom)
        assertFalse(row.denied)
        // Default for the risk-based mapping is not null — the row shows it.
        assertTrue(row.effectiveMode.name.isNotBlank())
    }

    @Test
    fun `breakdown appears in the summary for history with pressure`() {
        val now = System.currentTimeMillis()
        val state = PolicyScreenState(
            engineSource = { PolicyEngine() },
            historySource = {
                listOf(
                    record("Send", PolicyDecision.REQUIRE_APPROVAL, atMs = now),
                    record("Send", PolicyDecision.REQUIRE_APPROVAL, atMs = now),
                    record("OpenApp", PolicyDecision.AUTO_EXECUTE, atMs = now),
                )
            },
        )
        state.refresh()

        val content = state.model.value as PolicyUiModel.Content
        assertEquals("Send", content.breakdown.first().actionClass)
        assertEquals(2, content.breakdown.first().needsHuman)
        assertEquals(2, content.breakdown.size)
    }

    @Test
    fun `empty history is an honest empty state - no crash, no fabricated rows`() {
        val state = PolicyScreenState(engineSource = { PolicyEngine() }, historySource = { emptyList() })
        state.refresh()

        val content = state.model.value as PolicyUiModel.Content
        assertTrue(content.records.isEmpty())
        assertTrue(content.breakdown.isEmpty())
        assertEquals(0, content.summary.total)
        // Curated rows still render so the user can set policy before any decision.
        assertTrue(content.rows.isNotEmpty())
    }

    @Test
    fun `export success reports the written path`() {
        val now = System.currentTimeMillis()
        val state = PolicyScreenState(
            engineSource = { PolicyEngine() },
            historySource = { listOf(record("Send", PolicyDecision.REQUIRE_APPROVAL, atMs = now)) },
            exporter = { Result.success(Paths.get("/home/me/.aegis/policy-audit-1.csv")) },
        )
        state.refresh()

        state.export()

        assertEquals(ExportState.Done("/home/me/.aegis/policy-audit-1.csv"), state.exportState.value)
    }

    @Test
    fun `export failure surfaces the reason`() {
        val now = System.currentTimeMillis()
        val state = PolicyScreenState(
            engineSource = { PolicyEngine() },
            historySource = { listOf(record("Send", PolicyDecision.DENY, atMs = now)) },
            exporter = { Result.failure(IllegalStateException("disk full")) },
        )
        state.refresh()

        state.export()

        assertEquals(ExportState.Failed("disk full"), state.exportState.value)
    }

    @Test
    fun `export of an empty history is an honest failure, not a no-op`() {
        val state = PolicyScreenState(engineSource = { PolicyEngine() }, historySource = { emptyList() })
        state.refresh()

        state.export()

        val failed = state.exportState.value as ExportState.Failed
        assertTrue(failed.message.contains("Nothing to export"))
    }

    @Test
    fun `export before the first refresh is a no-op`() {
        val state = PolicyScreenState(engineSource = { PolicyEngine() }, historySource = { emptyList() })

        state.export() // model is still Loading

        assertEquals(ExportState.Idle, state.exportState.value)
    }

    @Test
    fun `export respects the active decision filter`() {
        val now = System.currentTimeMillis()
        var exported: List<PolicyAuditRecord>? = null
        val state = PolicyScreenState(
            engineSource = { PolicyEngine() },
            historySource = {
                listOf(
                    record("Send", PolicyDecision.REQUIRE_APPROVAL, atMs = now),
                    record("OpenApp", PolicyDecision.AUTO_EXECUTE, atMs = now),
                )
            },
            exporter = { records ->
                exported = records
                Result.success(Paths.get("/home/me/.aegis/policy-audit-1.csv"))
            },
        )
        state.refresh()
        state.setDecisionFilter(PolicyDecision.REQUIRE_APPROVAL)

        state.export()

        assertEquals(listOf("Send"), exported?.map { it.actionClass })
    }
}
