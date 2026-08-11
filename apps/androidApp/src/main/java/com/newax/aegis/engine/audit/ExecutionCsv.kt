package com.newax.aegis.engine.audit

/**
 * RFC-4180 CSV rendering for the execution audit trail — the machine-readable
 * twin of the Goals screen's "Recent runs" section, mirroring the desktop
 * app's `AuditExporter` columns as far as the local record supports (the
 * desktop entry carries reason/tiers; this one derives task count and optional
 * end times from the Android record shape). Pure Kotlin with zero Android
 * imports: the escaping rules (fields with a comma, quote, or newline are
 * quoted; internal quotes doubled) are the correctness-sensitive part and are
 * fully unit-testable off-device. Only audit references are written — never
 * credentials or command content.
 */
object ExecutionCsv {

    private val HEADER = listOf(
        "goal_id", "goal_description", "outcome", "task_count",
        "started_ms", "completed_ms", "duration_ms",
    )

    /** Renders the trail as CSV with a header row. Input order is preserved. */
    fun csv(entries: List<ExecutionAuditEntry>): String = buildString {
        append(HEADER.joinToString(",")).append("\r\n")
        entries.forEach { entry ->
            listOf(
                entry.goalId,
                entry.goalDescription,
                entry.outcome.name,
                entry.tasks.size.toString(),
                entry.startedMs.toString(),
                entry.finishedMs?.toString().orEmpty(),
                entry.durationMs?.toString().orEmpty(),
            ).joinTo(this, ",") { csvField(it) }.append("\r\n")
        }
    }

    private fun csvField(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
