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

    @Test
    fun `csv emits the header row`() {
        val csv = AuditExporter.csv(emptyList())
        val header = csv.lineSequence().first()
        assertEquals(
            "goal_id,goal_description,outcome,reason,tiers,task_count,started_ms,completed_ms,duration_ms",
            header
        )
        assertEquals(1, csv.lineSequence().count())
    }

    @Test
    fun `csv renders a full entry row`() {
        val csv = AuditExporter.csv(
            listOf(entry("g1", reason = "cap not ready", outcome = "BLOCKED", completedMs = 5_000L))
        )
        val row = csv.lineSequence().toList()[1]
        assertTrue(row.contains("\"g1\""))
        assertTrue(row.contains("open spotify"))
        assertTrue(row.contains("BLOCKED"))
        assertTrue(row.contains("EXACT_TARGET"))
        assertTrue(row.contains("2"))
        assertTrue(row.contains("5000"))
        assertTrue(row.contains("4000")) // durationMs
        assertTrue(row.contains("cap not ready"))
    }

    @Test
    fun `csv escapes commas quotes and newlines in reason`() {
        val entry = entry("g1", outcome = "BLOCKED", reason = "a, \"quoted\" and\nnewline")
        val csv = AuditExporter.csv(listOf(entry))
        val row = csv.lineSequence().toList()[1]
        // RFC-4180: quoted, internal quotes doubled, and the embedded newline stays inside the field.
        assertTrue(row.startsWith("\"g1\""))
        assertTrue(row.contains("\"a, \"\"quoted\"\" and\nnewline\""))
    }

    @Test
    fun `csv joins tiers with semicolons inside one quoted field`() {
        val csv = AuditExporter.csv(listOf(entry("g1", tiers = listOf("EXACT_TARGET", "PROCESS_LAUNCH"))))
        val row = csv.lineSequence().toList()[1]
        assertTrue(row.contains("\"EXACT_TARGET;PROCESS_LAUNCH\""))
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
