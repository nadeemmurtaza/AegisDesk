package com.newax.aegis.desktop

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.authority.PolicyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Desktop authority spine — the file-backed store, the audit codec, the ring
 * cap, the approval-pressure breakdown, and the process holder. Uses temp
 * files for every disk touch (the same convention as GoalsStoreTest), and
 * never touches the real ~/.aegis/ files.
 */
class DesktopPolicyTest {

    private fun tmpFile(prefix: String): Path =
        Files.createTempFile(prefix, ".json").also { Files.deleteIfExists(it) }

    private fun record(
        actionClass: String,
        decision: PolicyDecision,
        mode: PolicyMode = PolicyMode.APPROVAL,
        atMs: Long = System.currentTimeMillis(),
    ): PolicyAuditRecord = PolicyAuditRecord(
        actionClass = actionClass,
        actionSummary = "test action",
        origin = ActionOrigin.AGENT,
        risk = RiskLevel.MEDIUM,
        mode = mode,
        decision = decision,
        reason = "test",
        auditedAtMs = atMs,
    )

    @Test
    fun `FilePolicyStore persists mode overrides and denies across instances`() {
        val file = tmpFile("policy-store")
        FilePolicyStore(file).apply {
            setModeOverride("Send", PolicyMode.STRONG_CONFIRMATION)
            setDenied("DeleteFile", true)
        }

        val reloaded = FilePolicyStore(file)
        assertEquals(PolicyMode.STRONG_CONFIRMATION, reloaded.modeOverride("Send"))
        assertTrue(reloaded.isDenied("DeleteFile"))
        assertNull(reloaded.modeOverride("CreateEvent"))
        assertFalse(reloaded.isDenied("CreateEvent"))
    }

    @Test
    fun `corrupt or missing policy file starts from defaults`() {
        val missing = tmpFile("policy-missing")
        Files.deleteIfExists(missing)
        val empty = FilePolicyStore(missing)
        assertNull(empty.modeOverride("Send"))
        assertFalse(empty.isDenied("Send"))

        val corrupt = tmpFile("policy-corrupt")
        Files.writeString(corrupt, "{ not json !!")
        val safe = FilePolicyStore(corrupt)
        assertNull(safe.modeOverride("Send"))
        assertFalse(safe.isDenied("Send"))
    }

    @Test
    fun `audit codec round-trips every decision and is corrupt-safe`() {
        val records = listOf(
            record("Send", PolicyDecision.REQUIRE_APPROVAL),
            record("OpenApp", PolicyDecision.AUTO_EXECUTE, mode = PolicyMode.AUTO),
            record("DeleteFile", PolicyDecision.DENY, mode = PolicyMode.APPROVAL),
            record("CreateEvent", PolicyDecision.REQUIRE_STRONG, mode = PolicyMode.STRONG_CONFIRMATION),
        )
        val encoded = PolicyAuditCodec.encode(records)
        val decoded = PolicyAuditCodec.decode(encoded)!!

        assertEquals(records.size, decoded.size)
        assertEquals("Send", decoded[0].actionClass)
        assertEquals(PolicyDecision.REQUIRE_APPROVAL, decoded[0].decision)
        assertEquals(PolicyMode.AUTO, decoded[1].mode)
        assertEquals(PolicyDecision.DENY, decoded[2].decision)
        assertEquals(PolicyDecision.REQUIRE_STRONG, decoded[3].decision)

        assertNull("corrupt JSON → null, never a crash", PolicyAuditCodec.decode("{ nope"))
        assertNull("unknown version → null", PolicyAuditCodec.decode("{\"v\":99,\"records\":[]}"))
    }

    @Test
    fun `appendPolicyAudit keeps the newest records within the ring cap`() {
        var records: List<PolicyAuditRecord> = emptyList()
        repeat(250) { i ->
            records = appendPolicyAudit(records, record("Send", PolicyDecision.AUTO_EXECUTE, atMs = i.toLong()))
        }
        assertEquals(MAX_POLICY_AUDIT_RECORDS, records.size)
        assertEquals(50L, records.first().auditedAtMs)
        assertEquals(249L, records.last().auditedAtMs)
    }

    @Test
    fun `actionClassBreakdown counts prompts and denies separately, sorted by pressure`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            record("Send", PolicyDecision.REQUIRE_APPROVAL, atMs = now),
            record("Send", PolicyDecision.REQUIRE_APPROVAL, atMs = now),
            record("Send", PolicyDecision.DENY, atMs = now),
            record("OpenApp", PolicyDecision.AUTO_EXECUTE, mode = PolicyMode.AUTO, atMs = now),
            record("CreateEvent", PolicyDecision.REQUIRE_STRONG, atMs = now),
        )
        val breakdown = actionClassBreakdown(records)

        assertEquals(3, breakdown.size)
        // Send prompts twice (DENY is a block, not a prompt) → most pressure first.
        val send = breakdown[0]
        assertEquals("Send", send.actionClass)
        assertEquals(3, send.total)
        assertEquals(2, send.needsHuman)
        assertEquals(1, send.denied)
        assertEquals(0, send.autoExecuted)

        assertEquals("CreateEvent", breakdown[1].actionClass)
        assertEquals(1, breakdown[1].needsHuman)

        val openApp = breakdown[2]
        assertEquals("OpenApp", openApp.actionClass)
        assertEquals(1, openApp.autoExecuted)
        assertEquals(0, openApp.needsHuman)
    }

    @Test
    fun `actionClassBreakdown is deterministic and empty-safe`() {
        assertTrue(actionClassBreakdown(emptyList()).isEmpty())

        val now = System.currentTimeMillis()
        val records = listOf(
            record("Send", PolicyDecision.AUTO_EXECUTE, atMs = now),
            record("Send", PolicyDecision.AUTO_EXECUTE, atMs = now),
            record("OpenApp", PolicyDecision.AUTO_EXECUTE, atMs = now),
        )
        val breakdown = actionClassBreakdown(records)
        // Equal pressure → total desc, then class name: Send (2) before OpenApp (1).
        assertEquals(listOf("Send", "OpenApp"), breakdown.map { it.actionClass })
        assertEquals(breakdown, actionClassBreakdown(records))
    }

    @Test
    fun `holder evaluates through one engine, records audits, and persists across re-init`() {
        DesktopPolicyHolder.resetForTest()
        try {
            val policyFile = tmpFile("holder-policy")
            val auditFile = tmpFile("holder-audit")
            DesktopPolicyHolder.init(policyFile, auditFile)

            val engine = DesktopPolicyHolder.engine()
            engine.setModeOverride("Send", PolicyMode.APPROVAL)
            val evaluation = DesktopPolicyHolder.evaluateOrNull(ProposedAction.Send("hi"), ActionOrigin.AGENT)!!
            assertEquals(PolicyDecision.REQUIRE_APPROVAL, evaluation.decision)
            assertFalse("non-AUTO must not allow autonomous execution", evaluation.decision.allowsAutonomousExecution)

            val history = DesktopPolicyHolder.auditHistory()
            assertEquals(1, history.size)
            assertEquals("Send", history.first().actionClass)

            // Re-init from the same files → the trail survives the restart.
            DesktopPolicyHolder.resetForTest()
            DesktopPolicyHolder.init(policyFile, auditFile)
            assertEquals(1, DesktopPolicyHolder.auditHistory().size)
            assertEquals(PolicyMode.APPROVAL, DesktopPolicyHolder.engine().modeOverride("Send"))

            DesktopPolicyHolder.clearAuditHistory()
            assertTrue(DesktopPolicyHolder.auditHistory().isEmpty())
            // And the persisted ring is empty after the clear, across a reload.
            DesktopPolicyHolder.resetForTest()
            DesktopPolicyHolder.init(policyFile, auditFile)
            assertTrue(DesktopPolicyHolder.auditHistory().isEmpty())
        } finally {
            DesktopPolicyHolder.resetForTest()
        }
    }

    @Test
    fun `evaluateOrNull returns null before init - execution degrades as before`() {
        DesktopPolicyHolder.resetForTest()
        assertNull(DesktopPolicyHolder.evaluateOrNull(ProposedAction.Send("hi"), ActionOrigin.AGENT))
    }
}
