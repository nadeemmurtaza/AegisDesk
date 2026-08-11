package com.newax.aegis.engine.audit

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.authority.PolicyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android parity — the policy-decision CSV renderer. Pure JVM: the escaping
 * rules are the correctness-sensitive part and must be RFC-4180-correct
 * (fields with commas/quotes/newlines quoted, quotes doubled), with the same
 * header and columns as the desktop `PolicyExporter` so exports are
 * interchangeable across platforms.
 */
class PolicyCsvTest {

    private fun record(
        actionClass: String,
        decision: PolicyDecision,
        reason: String = "test",
        auditedAtMs: Long = 5_000L,
        actionSummary: String = "open spotify",
    ) = PolicyAuditRecord(
        actionClass = actionClass,
        actionSummary = actionSummary,
        origin = ActionOrigin.AGENT,
        risk = RiskLevel.HIGH_IMPACT_SYSTEM,
        mode = PolicyMode.APPROVAL,
        decision = decision,
        reason = reason,
        auditedAtMs = auditedAtMs,
    )

    @Test
    fun `csv emits the header row`() {
        val csv = PolicyCsv.csv(emptyList())
        val header = csv.lineSequence().first()
        assertEquals(
            "action_class,action_summary,origin,risk,mode,decision,reason,audited_at_ms",
            header
        )
        assertEquals(1, csv.lineSequence().count())
    }

    @Test
    fun `csv renders a full record row`() {
        val csv = PolicyCsv.csv(
            listOf(record("Send", PolicyDecision.REQUIRE_APPROVAL, reason = "not configured"))
        )
        val row = csv.lineSequence().toList()[1]
        assertTrue(row.contains("\"Send\""))
        assertTrue(row.contains("open spotify"))
        assertTrue(row.contains("AGENT"))
        assertTrue(row.contains("HIGH_IMPACT_SYSTEM"))
        assertTrue(row.contains("APPROVAL"))
        assertTrue(row.contains("REQUIRE_APPROVAL"))
        assertTrue(row.contains("not configured"))
        assertTrue(row.contains("5000"))
    }

    @Test
    fun `csv escapes commas quotes and newlines in reason`() {
        val csv = PolicyCsv.csv(
            listOf(record("Send", PolicyDecision.DENY, reason = "a, \"quoted\" and\nnewline"))
        )
        val row = csv.lineSequence().toList()[1]
        // RFC-4180: quoted, internal quotes doubled, and the embedded newline stays inside the field.
        assertTrue(row.startsWith("\"Send\""))
        assertTrue(row.contains("\"a, \"\"quoted\"\" and\nnewline\""))
    }

    @Test
    fun `csv preserves input order`() {
        val csv = PolicyCsv.csv(
            listOf(record("Send", PolicyDecision.AUTO_EXECUTE, auditedAtMs = 1_000L), record("OpenApp", PolicyDecision.DENY, auditedAtMs = 2_000L))
        )
        val rows = csv.lineSequence().toList().drop(1)
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains("AUTO_EXECUTE"))
        assertTrue(rows[1].contains("DENY"))
    }
}
