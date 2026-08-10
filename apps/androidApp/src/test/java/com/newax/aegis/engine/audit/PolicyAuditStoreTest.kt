package com.newax.aegis.engine.audit

import com.newax.aegis.PolicyHolder
import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.authority.PolicyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy-decision history persistence. Pure JVM, mirroring ExecutionAuditTest's
 * convention: the ring-cap append logic and the holder's pre-init safety are
 * testable here; the org.json codec and the kv_store store are Android-side
 * (same pattern as the goal snapshot and execution audit codecs).
 */
class PolicyAuditStoreTest {

    private fun record(id: Int, decision: PolicyDecision = PolicyDecision.AUTO_EXECUTE) =
        PolicyAuditRecord(
            actionClass = "Send",
            actionSummary = "send message $id",
            origin = ActionOrigin.USER,
            risk = RiskLevel.MEDIUM,
            mode = PolicyMode.CONFIGURABLE,
            decision = decision,
            reason = "mode CONFIGURABLE and toggle 'send' is on",
            auditedAtMs = id.toLong()
        )

    @Test
    fun `append keeps the newest records within the cap`() {
        val capped = (1..30)
            .map { record(it) }
            .fold(emptyList<PolicyAuditRecord>()) { acc, r -> appendPolicyAudit(acc, r, maxSize = 10) }

        assertEquals(10, capped.size)
        assertEquals(21L, capped.first().auditedAtMs)
        assertEquals(30L, capped.last().auditedAtMs)
    }

    @Test
    fun `append preserves order and passes through small lists`() {
        val result = appendPolicyAudit(listOf(record(1)), record(2), maxSize = 10)

        assertEquals(listOf(1L, 2L), result.map { it.auditedAtMs })
    }

    @Test
    fun `append records every decision type`() {
        val decisions = PolicyDecision.entries
            .mapIndexed { idx, decision -> record(idx, decision) }
            .fold(emptyList<PolicyAuditRecord>()) { acc, r -> appendPolicyAudit(acc, r, maxSize = 10) }

        assertEquals(PolicyDecision.entries.size, decisions.size)
        assertEquals(PolicyDecision.entries.toList(), decisions.map { it.decision })
    }

    @Test
    fun `actionClassBreakdown groups counts and sorts by human approval pressure`() {
        val breakdown = actionClassBreakdown(
            listOf(
                record(1, PolicyDecision.AUTO_EXECUTE).copy(actionClass = "Send"),
                record(2, PolicyDecision.REQUIRE_APPROVAL).copy(actionClass = "Send"),
                record(3, PolicyDecision.REQUIRE_APPROVAL).copy(actionClass = "Send"),
                record(4, PolicyDecision.REQUIRE_STRONG).copy(actionClass = "RunScript"),
                record(5, PolicyDecision.DENY).copy(actionClass = "DeleteFile"),
                record(6, PolicyDecision.AUTO_EXECUTE).copy(actionClass = "DeleteFile"),
            )
        )

        // Send prompts twice; DeleteFile and RunScript once each — the tie is
        // broken by total (DeleteFile is busier), then deterministically by name.
        assertEquals(listOf("Send", "DeleteFile", "RunScript"), breakdown.map { it.actionClass })

        val send = breakdown.first { it.actionClass == "Send" }
        assertEquals(3, send.total)
        assertEquals(1, send.autoExecuted)
        assertEquals(2, send.needsHuman)
        assertEquals(0, send.denied)

        // A DENY is never a prompt: counted separately, not in needsHuman.
        val delete = breakdown.first { it.actionClass == "DeleteFile" }
        assertEquals(2, delete.total)
        assertEquals(1, delete.needsHuman)
        assertEquals(1, delete.denied)
    }

    @Test
    fun `actionClassBreakdown is empty for an empty history`() {
        assertTrue(actionClassBreakdown(emptyList()).isEmpty())
    }

    @Test
    fun `actionClassBreakdown tie-breaks deterministically by name`() {
        val breakdown = actionClassBreakdown(
            listOf(
                record(1, PolicyDecision.REQUIRE_APPROVAL).copy(actionClass = "B"),
                record(2, PolicyDecision.REQUIRE_APPROVAL).copy(actionClass = "A"),
            )
        )

        assertEquals(listOf("A", "B"), breakdown.map { it.actionClass })
    }

    @Test
    fun `holder is inert before init`() {
        // No Context/dao in JVM tests: the history is empty and clear is a safe
        // no-op — nothing crashes before bootstrap wires the engine and store.
        assertTrue(PolicyHolder.auditHistory().isEmpty())
        assertTrue(PolicyHolder.recentAudits(8).isEmpty())
        PolicyHolder.clearAuditHistory()
        assertTrue(PolicyHolder.auditHistory().isEmpty())
    }
}
