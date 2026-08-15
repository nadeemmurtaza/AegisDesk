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
        risk = RiskLevel.HIGH,
        mode = PolicyMode.APPROVAL,
        decision = decision,
        reason = reason,
        auditedAtMs = auditedAtMs,
    )

    // Exact-string assertions rather than picking rows out with lineSequence():
    // RFC-4180 fields may contain embedded newlines, so splitting on newlines
    // cannot recover a record — which is why the escaping case below used to
    // fail. They also pin CRLF terminators (trailing one included) and minimal
    // quoting: only a comma, quote, CR or LF forces quotes.

    private val header =
        "action_class,action_summary,origin,risk,mode,decision,reason,audited_at_ms"

    @Test
    fun `csv emits only the header row when there is nothing to export`() {
        assertEquals("$header\r\n", PolicyExporter.csv(emptyList()))
    }

    @Test
    fun `csv renders a full record row`() {
        val csv = PolicyExporter.csv(
            listOf(record("Send", PolicyDecision.REQUIRE_APPROVAL, reason = "not configured"))
        )
        assertEquals(
            "$header\r\n" +
                "Send,open spotify,AGENT,HIGH,APPROVAL,REQUIRE_APPROVAL,not configured,5000\r\n",
            csv,
        )
    }

    @Test
    fun `csv escapes commas quotes and newlines in reason`() {
        val csv = PolicyExporter.csv(
            listOf(record("Send", PolicyDecision.DENY, reason = "a, \"quoted\" and\nnewline"))
        )
        // The embedded newline stays inside the quoted field — which is exactly
        // why a record cannot be recovered by splitting on newlines.
        assertEquals(
            "$header\r\n" +
                "Send,open spotify,AGENT,HIGH,APPROVAL,DENY,\"a, \"\"quoted\"\" and\nnewline\",5000\r\n",
            csv,
        )
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
