package com.newax.aegis.desktop

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.authority.PolicyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Desktop parity — the policy-decision CSV exporter. Pure JVM: the renderer
 * must be RFC-4180-correct (fields with commas/quotes/newlines quoted, quotes
 * doubled), and the file path must be honest — written bytes equal the renderer
 * output, and a failed export is a failed [Result], never a silent no-op.
 */
class PolicyExporterTest {

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
        val csv = PolicyExporter.csv(emptyList())
        val header = csv.lineSequence().first()
        assertEquals(
            "action_class,action_summary,origin,risk,mode,decision,reason,audited_at_ms",
            header
        )
        assertEquals(1, csv.lineSequence().count())
    }

    @Test
    fun `csv renders a full record row`() {
        val csv = PolicyExporter.csv(
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
        val csv = PolicyExporter.csv(
            listOf(record("Send", PolicyDecision.DENY, reason = "a, \"quoted\" and\nnewline"))
        )
        val row = csv.lineSequence().toList()[1]
        // RFC-4180: quoted, internal quotes doubled, and the embedded newline stays inside the field.
        assertTrue(row.startsWith("\"Send\""))
        assertTrue(row.contains("\"a, \"\"quoted\"\" and\nnewline\""))
    }

    @Test
    fun `writeCsv persists exactly what csv renders`() {
        val file = tempDir().resolve("policy-audit-test.csv")
        val records = listOf(record("Send", PolicyDecision.AUTO_EXECUTE), record("OpenApp", PolicyDecision.DENY))

        PolicyExporter.writeCsv(records, file)

        assertTrue(Files.isRegularFile(file))
        assertEquals(PolicyExporter.csv(records), Files.readString(file))
    }

    @Test
    fun `exportCsv writes a timestamped file under the given dir`() {
        val dir = tempDir()
        val records = listOf(record("Send", PolicyDecision.REQUIRE_APPROVAL))

        val result = PolicyExporter.exportCsv(records, dir)

        assertTrue("export must succeed", result.isSuccess)
        val file = result.getOrThrow()
        assertTrue(file.fileName.toString().startsWith("policy-audit-"))
        assertTrue(file.fileName.toString().endsWith(".csv"))
        assertEquals(PolicyExporter.csv(records), Files.readString(file))
    }

    @Test
    fun `exportCsv fails honestly on an unwritable dir`() {
        val dir = tempDir().resolve("nope")
        Files.createFile(dir) // a regular file cannot be a directory

        val result = PolicyExporter.exportCsv(listOf(record("Send", PolicyDecision.DENY)), dir)

        assertTrue("export must report failure, not crash or no-op", result.isFailure)
    }

    private fun tempDir(): java.nio.file.Path =
        Files.createTempDirectory("policy-exporter-test")
}
