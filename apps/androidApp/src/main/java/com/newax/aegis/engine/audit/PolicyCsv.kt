package com.newax.aegis.engine.audit

import com.newax.aegis.authority.PolicyAuditRecord

/**
 * RFC-4180 CSV rendering for the policy-decision trail — the machine-readable
 * twin of [PolicyHistoryScreen], column-for-column identical to the desktop
 * app's `PolicyExporter` so exports are interchangeable across platforms. Pure
 * Kotlin with zero Android imports: the escaping rules (fields with a comma,
 * quote, or newline are quoted; internal quotes doubled) are the
 * correctness-sensitive part and are fully unit-testable off-device. Only
 * decision metadata is written — never credentials or command content.
 */
object PolicyCsv {

    private val HEADER = listOf(
        "action_class", "action_summary", "origin", "risk", "mode",
        "decision", "reason", "audited_at_ms",
    )

    /** Renders the decision trail as CSV with a header row. Input order is preserved (newest-first as held). */
    fun csv(records: List<PolicyAuditRecord>): String = buildString {
        append(HEADER.joinToString(",")).append("\r\n")
        records.forEach { record ->
            listOf(
                record.actionClass,
                record.actionSummary,
                record.origin.name,
                record.risk.name,
                record.mode.name,
                record.decision.name,
                record.reason,
                record.auditedAtMs.toString(),
            ).joinTo(this, ",") { csvField(it) }.append("\r\n")
        }
    }

    private fun csvField(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
