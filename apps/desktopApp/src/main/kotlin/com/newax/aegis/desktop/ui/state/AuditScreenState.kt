package com.newax.aegis.desktop.ui.state

import com.newax.aegis.desktop.AuditExporter
import com.newax.aegis.desktop.AuditSummary
import com.newax.aegis.desktop.ExecutionAudit
import com.newax.aegis.desktop.ExecutionAuditEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/** The audit trail model: loading (before the first snapshot), error, or content (with the rolled-up summary). */
sealed interface AuditUiModel {
    data object Loading : AuditUiModel
    data class Error(val message: String) : AuditUiModel
    data class Content(val entries: List<ExecutionAuditEntry>, val summary: AuditSummary) : AuditUiModel
}

/** Where an export attempt ended: nothing yet, written to [path], or failed with [message]. */
sealed interface ExportState {
    data object Idle : ExportState
    data class Done(val path: String) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * Audit screen state — the plain-Kotlin, fully testable core of the desktop
 * Audit tab (the "Recent runs" block of `printGoals` lifted into a screen that
 * shows the *whole* trail). The trail source and the CSV writer are injectable
 * seams ([auditSource] / [exporter]) so every decision — ordering, empty
 * handling, failure surfacing — is testable without touching the process-wide
 * [ExecutionAudit] log or the disk.
 *
 * The screen renders [model] newest-first (the audit is a trail, read top-down
 * from the latest run) and [exportState] shows the outcome of the last export
 * — an honest path or an honest failure, never a silent no-op (AGENTS.md R9).
 */
class AuditScreenState(
    private val auditSource: () -> List<ExecutionAuditEntry> = { ExecutionAudit.all() },
    private val exporter: (List<ExecutionAuditEntry>) -> Result<Path> =
        { AuditExporter.exportCsv(it) },
) {

    private val _model = MutableStateFlow<AuditUiModel>(AuditUiModel.Loading)
    val model: StateFlow<AuditUiModel> = _model.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    /** Reloads the trail (newest first) and its summary. Loading → Content/Error. */
    fun refresh() {
        try {
            val entries = auditSource().sortedByDescending { it.completedMs }
            _model.value = AuditUiModel.Content(entries, AuditSummary.of(entries))
        } catch (e: Exception) {
            _model.value = AuditUiModel.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Exports the currently shown trail to CSV. An empty trail and a writer
     * failure are both explicit [ExportState.Failed] states — nothing to
     * export is a real condition, not a silent no-op.
     */
    fun export() {
        val entries = (_model.value as? AuditUiModel.Content)?.entries
            ?: return
        if (entries.isEmpty()) {
            _exportState.value = ExportState.Failed("Nothing to export — no runs recorded yet.")
            return
        }
        exporter(entries).fold(
            onSuccess = { path -> _exportState.value = ExportState.Done(path.toString()) },
            onFailure = { e -> _exportState.value = ExportState.Failed(e.message ?: e.javaClass.simpleName) },
        )
    }
}
