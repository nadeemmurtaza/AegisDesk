package com.newax.aegis.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * CSV export for the execution audit trail (Track B follow-up — the machine-
 * readable twin of the audit screen). [csv] is a pure RFC-4180 renderer (the
 * escaping rules live once in [CsvFiles]), [writeCsv] persists it atomically
 * and [exportCsv] is the one-call path the UI uses. Only audit references are
 * written — never credentials or command content (invariant 4 holds at record
 * time and is preserved here).
 */
object AuditExporter {

    private val HEADER = listOf(
        "goal_id", "goal_description", "outcome", "reason", "tiers",
        "task_count", "started_ms", "completed_ms", "duration_ms",
    )

    /** Renders the trail as CSV with a header row. Input order is preserved (what the screen shows). */
    fun csv(entries: List<ExecutionAuditEntry>): String = CsvFiles.render(
        HEADER,
        entries.map { entry ->
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
            )
        },
    )

    /** Writes [csv] output to [file] atomically (temp + move — a crash mid-write never corrupts an earlier export). */
    @Throws(IOException::class)
    fun writeCsv(entries: List<ExecutionAuditEntry>, file: Path): Path =
        CsvFiles.write(csv(entries), file)

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
        val file = dir.resolve("audit-${CsvFiles.timestamp()}.csv")
        writeCsv(entries, file)
        file
    }

    fun defaultExportDir(): Path = CsvFiles.defaultDir()
}
