package com.newax.aegis.desktop

import com.newax.aegis.authority.PolicyAuditRecord
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * CSV export for the policy-decision audit trail — the machine-readable twin
 * of the Policy tab's decision history (mirroring [AuditExporter] for the
 * execution trail). [csv] is a pure RFC-4180 renderer (escaping lives once in
 * [CsvFiles]), [writeCsv] persists it atomically, and [exportCsv] is the
 * one-call path the UI uses: `policy-audit-<timestamp>.csv` under `~/.aegis`.
 * Decision metadata only — never credentials or command content.
 */
object PolicyExporter {

    private val HEADER = listOf(
        "action_class", "action_summary", "origin", "risk", "mode",
        "decision", "reason", "audited_at_ms",
    )

    /** Renders the decision trail as CSV with a header row. Input order is preserved (what the screen shows). */
    fun csv(records: List<PolicyAuditRecord>): String = CsvFiles.render(
        HEADER,
        records.map { record ->
            listOf(
                record.actionClass,
                record.actionSummary,
                record.origin.name,
                record.risk.name,
                record.mode.name,
                record.decision.name,
                record.reason,
                record.auditedAtMs.toString(),
            )
        },
    )

    /** Writes [csv] output to [file] atomically (temp + move — a crash mid-write never corrupts an earlier export). */
    @Throws(IOException::class)
    fun writeCsv(records: List<PolicyAuditRecord>, file: Path): Path =
        CsvFiles.write(csv(records), file)

    /**
     * The one-call export path: writes `policy-audit-<yyyyMMdd-HHmmss>.csv`
     * under [dir] (default `~/.aegis`) and returns the file. Failures
     * (unwritable dir, disk error) surface as a failed [Result] — never a
     * silent no-op.
     */
    fun exportCsv(
        records: List<PolicyAuditRecord>,
        dir: Path = defaultExportDir(),
    ): Result<Path> = runCatching {
        Files.createDirectories(dir)
        val file = dir.resolve("policy-audit-${CsvFiles.timestamp()}.csv")
        writeCsv(records, file)
        file
    }

    fun defaultExportDir(): Path = CsvFiles.defaultDir()
}
