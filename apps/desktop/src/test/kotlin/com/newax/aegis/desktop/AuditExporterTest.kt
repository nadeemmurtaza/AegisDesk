package com.newax.aegis.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Track B follow-up — the audit CSV exporter. Pure JVM: the renderer must be
 * RFC-4180-correct (fields with commas/quotes/newlines quoted, quotes doubled),
 * and the file path must be honest — written bytes equal the renderer output,
 * and a failed export is a failed [Result], never a silent no-op.
 */
class AuditExporterTest {

    private fun entry(
        id: String,
        description: String = "open spotify",
        outcome: String = "COMPLETED",
        reason: String? = null,
        tiers: List<String> = listOf("EXACT_TARGET"),
        taskCount: Int = 2,
        startedMs: Long = 1_000L,
        completedMs: Long = 2_000L,
    ) = ExecutionAuditEntry(
        goalId = id,
        goalDescription = description,
        outcome = outcome,
        reason = reason,
        tiers = tiers,
        taskCount = taskCount,
        startedMs = startedMs,
        completedMs = completedMs,
    )

    // These cases assert the exact rendered string rather than picking rows out
    // with lineSequence(). That was not a style choice: RFC-4180 fields may
    // contain embedded newlines, so splitting on newlines cannot recover a
    // record — the escaping case below was failing for exactly that reason.
    // Exact-string assertions also pin the two things the old `contains` checks
    // silently disagreed with the renderer about: CRLF terminators (including a
    // trailing one, per RFC-4180) and minimal quoting — only fields containing
    // a comma, quote, CR or LF are quoted.

    private val header =
        "goal_id,goal_description,outcome,reason,tiers,task_count,started_ms,completed_ms,duration_ms"

    @Test
    fun `csv emits only the header row when there is nothing to export`() {
        assertEquals("$header\r\n", AuditExporter.csv(emptyList()))
    }

    @Test
    fun `csv renders a full entry row with durationMs derived`() {
        val csv = AuditExporter.csv(
            listOf(entry("g1", reason = "cap not ready", outcome = "BLOCKED", completedMs = 5_000L))
        )
        // 5000 - 1000 = 4000 is the derived durationMs, the one column that is
        // computed rather than copied.
        assertEquals(
            "$header\r\n" +
                "g1,open spotify,BLOCKED,cap not ready,EXACT_TARGET,2,1000,5000,4000\r\n",
            csv,
        )
    }

    @Test
    fun `csv escapes commas quotes and newlines in reason`() {
        val csv = AuditExporter.csv(
            listOf(entry("g1", outcome = "BLOCKED", reason = "a, \"quoted\" and\nnewline"))
        )
        // RFC-4180: the field is quoted, internal quotes are doubled, and the
        // embedded newline stays *inside* the field — which is why the record
        // cannot be recovered by splitting the output on newlines.
        assertEquals(
            "$header\r\n" +
                "g1,open spotify,BLOCKED,\"a, \"\"quoted\"\" and\nnewline\",EXACT_TARGET,2,1000,2000,1000\r\n",
            csv,
        )
    }

    @Test
    fun `csv joins tiers with semicolons so they stay one field`() {
        val csv = AuditExporter.csv(listOf(entry("g1", tiers = listOf("EXACT_TARGET", "PROCESS_LAUNCH"))))
        // A semicolon is not a CSV delimiter, so the joined value needs no
        // quoting — that is the point of joining with one rather than a comma.
        assertEquals(
            "$header\r\n" +
                "g1,open spotify,COMPLETED,,EXACT_TARGET;PROCESS_LAUNCH,2,1000,2000,1000\r\n",
            csv,
        )
    }

    @Test
    fun `writeCsv persists exactly what csv renders`() {
        val file = tempDir().resolve("audit-test.csv")
        val entries = listOf(entry("g1"), entry("g2", outcome = "BLOCKED", reason = "boom"))

        AuditExporter.writeCsv(entries, file)

        assertTrue(Files.isRegularFile(file))
        assertEquals(AuditExporter.csv(entries), Files.readString(file))
    }

    @Test
    fun `exportCsv writes a timestamped file under the given dir`() {
        val dir = tempDir()
        val entries = listOf(entry("g1"))

        val result = AuditExporter.exportCsv(entries, dir)

        assertTrue("export must succeed", result.isSuccess)
        val file = result.getOrThrow()
        assertTrue(file.fileName.toString().startsWith("audit-"))
        assertTrue(file.fileName.toString().endsWith(".csv"))
        assertEquals(AuditExporter.csv(entries), Files.readString(file))
    }

    @Test
    fun `exportCsv fails honestly on an unwritable dir`() {
        val dir = tempDir().resolve("nope")
        Files.createFile(dir) // a regular file cannot be a directory

        val result = AuditExporter.exportCsv(listOf(entry("g1")), dir)

        assertTrue("export must report failure, not crash or no-op", result.isFailure)
    }

    private fun tempDir(): java.nio.file.Path =
        Files.createTempDirectory("audit-exporter-test")
}
