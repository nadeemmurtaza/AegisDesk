package com.newax.aegis.desktop

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * CSV export for the execution audit trail (Track B follow-up — the machine-
 * readable twin of the audit screen). [csv] is a pure RFC-4180 renderer (every
 * field with a comma, quote, or newline is quoted and internal quotes doubled —
 * R12: data is data, never structure), so it is fully testable off-disk;
 * [writeCsv] persists it atomically and [exportCsv] is the one-call path the
 * UI and CLI use. Only audit references are written — never credentials or
 * command content (invariant 4 holds at record time and is preserved here).
 */
object AuditExporter {

    private val HEADER = listOf(
        "goal_id", "goal_description", "outcome", "reason", "tiers",
        "task_count", "started_ms", "completed_ms", "duration_ms",
    )

    /** Renders the trail as CSV with a header row. Input order is preserved (chronological). */
    fun csv(entries: List<ExecutionAuditEntry>): String = buildString {
        append(HEADER.joinToString(",")).append("\r\n")
        entries.forEach { entry ->
            listOf(
                entry.goalId,
                entry.goalDescription,
                entry.outcome,
                entry.reason.orEmpty(),
                entry.tiers.joinToString(";"),
                entry.taskCount.toString(),
                entry.startedMs.toString(),
                entry.completedMs.toString(),
                entry.durationMs.toString(),
            ).joinTo(this, ",") { csvField(it) }.append("\r\n")
        }
    }

    /** Writes [csv] output to [file] atomically (temp + move — a crash mid-write never corrupts an earlier export). */
    @Throws(IOException::class)
    fun writeCsv(entries: List<ExecutionAuditEntry>, file: Path): Path {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.write(tmp, csv(entries).toByteArray(Charsets.UTF_8))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        return file
    }

    /**
     * The one-call export path: writes `audit-<yyyyMMdd-HHmmss>.csv` under
     * [dir] (default `~/.aegis`) and returns the file. Failures (unwritable
     * dir, disk error) surface as a failed [Result] — never a silent no-op.
     */
    fun exportCsv(
        entries: List<ExecutionAuditEntry>,
        dir: Path = defaultExportDir(),
    ): Result<Path> = runCatching {
        Files.createDirectories(dir)
        val file = dir.resolve("audit-${exportTimestamp()}.csv")
        writeCsv(entries, file)
        file
    }

    fun defaultExportDir(): Path =
        Paths.get(System.getProperty("user.home") ?: ".", ".aegis")

    private fun csvField(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun exportTimestamp(): String =
        Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneId.systemDefault())
            .format(EXPORT_TIME_FORMATTER)

    private val EXPORT_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
}
