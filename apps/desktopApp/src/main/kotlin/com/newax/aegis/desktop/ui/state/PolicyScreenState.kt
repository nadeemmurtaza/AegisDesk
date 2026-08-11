package com.newax.aegis.desktop.ui.state

import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.authority.PolicyEngine
import com.newax.aegis.authority.PolicyMode
import com.newax.aegis.desktop.ActionClassStat
import com.newax.aegis.desktop.DesktopPolicyHolder
import com.newax.aegis.desktop.PolicyExporter
import com.newax.aegis.desktop.actionClassBreakdown
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/** One action class's policy row on the Policy tab — effective mode, override, deny. */
data class PolicyModeRow(
    val actionClass: String,
    val label: String,
    val description: String,
    val sample: ProposedAction,
    /** Filled by [PolicyScreenState.deriveRows] against the live engine; defaults are the pre-init state. */
    val effectiveMode: PolicyMode = PolicyMode.APPROVAL,
    val custom: Boolean = false,
    val denied: Boolean = false,
)

/** The policy tab model: loading (before the first snapshot), error, or content. */
sealed interface PolicyUiModel {
    data object Loading : PolicyUiModel
    data class Error(val message: String) : PolicyUiModel
    data class Content(
        val rows: List<PolicyModeRow>,
        /** Full history, newest first — the raw trail the filter slices. */
        val records: List<PolicyAuditRecord>,
        /** The same history filtered by [decisionFilter] (null = all), newest first. */
        val filteredRecords: List<PolicyAuditRecord>,
        /** Per-decision tallies over the *filtered* slice (so the summary follows the filter). */
        val summary: PolicySummary,
        /** Per-action-class approval pressure over the full history. */
        val breakdown: List<ActionClassStat>,
    ) : PolicyUiModel
}

/** Per-decision counts over a slice of the policy history. */
data class PolicySummary(
    val autoExecuted: Int,
    val approvals: Int,
    val strong: Int,
    val denied: Int,
) {
    val total: Int get() = autoExecuted + approvals + strong + denied

    companion object {
        fun of(records: List<PolicyAuditRecord>): PolicySummary = PolicySummary(
            autoExecuted = records.count { it.decision == PolicyDecision.AUTO_EXECUTE },
            approvals = records.count { it.decision == PolicyDecision.REQUIRE_APPROVAL },
            strong = records.count { it.decision == PolicyDecision.REQUIRE_STRONG },
            denied = records.count { it.decision == PolicyDecision.DENY },
        )
    }
}

/**
 * Policy tab state — the plain-Kotlin, fully testable core of the desktop
 * authority surface (the desktop twin of Android's Capabilities policy section
 * + policy history screen in one tab). It surfaces every action class's
 * effective policy mode with per-class controls (mode override, hard deny,
 * reset), the policy-decision audit trail with a decision filter, per-decision
 * summary stats, and the per-class approval-pressure breakdown.
 *
 * The engine, the history source, the clear action, and the CSV exporter are
 * injectable seams so every decision (row derivation, filter semantics, empty
 * handling, breakdown sorting, export outcome) is testable without the
 * process-wide [DesktopPolicyHolder] or the disk; the live defaults drive the
 * real holder. Mutations go through the one engine's [PolicyStore] — the only
 * path that changes a mode or deny state.
 */
class PolicyScreenState(
    private val engineSource: () -> PolicyEngine? = { DesktopPolicyHolder.engineOrNull() },
    private val historySource: () -> List<PolicyAuditRecord> = { DesktopPolicyHolder.auditHistory() },
    private val clearHistory: () -> Unit = { DesktopPolicyHolder.clearAuditHistory() },
    private val exporter: (List<PolicyAuditRecord>) -> Result<Path> =
        { PolicyExporter.exportCsv(it) },
) {

    private val _model = MutableStateFlow<PolicyUiModel>(PolicyUiModel.Loading)
    val model: StateFlow<PolicyUiModel> = _model.asStateFlow()

    private val _decisionFilter = MutableStateFlow<PolicyDecision?>(null)
    val decisionFilter: StateFlow<PolicyDecision?> = _decisionFilter.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    /** Reloads rows, history, summary, and breakdown. Loading → Content/Error. */
    fun refresh() {
        try {
            val engine = engineSource()
            val history = historySource()
            val filter = _decisionFilter.value
            val rows = deriveRows(engine, history)
            val filtered = if (filter == null) history else history.filter { it.decision == filter }
            _model.value = PolicyUiModel.Content(
                rows = rows,
                records = history.sortedByDescending { it.auditedAtMs },
                filteredRecords = filtered.sortedByDescending { it.auditedAtMs },
                summary = PolicySummary.of(filtered),
                breakdown = actionClassBreakdown(history),
            )
        } catch (e: Exception) {
            _model.value = PolicyUiModel.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Slices the trail by decision (null = all) and refreshes the summary. */
    fun setDecisionFilter(filter: PolicyDecision?) {
        _decisionFilter.value = filter
        refresh()
    }

    /** Sets a mode override for one class through the one engine, then refreshes. */
    fun setMode(actionClass: String, mode: PolicyMode) {
        engineSource()?.setModeOverride(actionClass, mode)
        refresh()
    }

    /** Toggles the hard deny for one class through the one engine, then refreshes. */
    fun setDenied(actionClass: String, denied: Boolean) {
        engineSource()?.setDenied(actionClass, denied)
        refresh()
    }

    /** Clears a class's override and deny — back to the risk-based default — then refreshes. */
    fun reset(actionClass: String) {
        val engine = engineSource() ?: return
        engine.clearModeOverride(actionClass)
        engine.setDenied(actionClass, false)
        refresh()
    }

    /** Wipes the recorded history (memory and persisted ring), then refreshes. */
    fun clear() {
        clearHistory()
        refresh()
    }

    /**
     * Exports the currently shown trail — the [PolicyUiModel.Content.filteredRecords]
     * slice, so the CSV matches what is on screen under the active decision
     * filter. An empty history and a writer failure are both explicit
     * [ExportState.Failed] states — nothing to export is a real condition, not
     * a silent no-op.
     */
    fun export() {
        val content = _model.value as? PolicyUiModel.Content ?: return
        val records = content.filteredRecords
        if (records.isEmpty()) {
            _exportState.value = ExportState.Failed(
                if (content.records.isEmpty()) "Nothing to export — no policy decisions recorded yet."
                else "Nothing to export — no decisions match the current filter."
            )
            return
        }
        exporter(records).fold(
            onSuccess = { path -> _exportState.value = ExportState.Done(path.toString()) },
            onFailure = { e -> _exportState.value = ExportState.Failed(e.message ?: e.javaClass.simpleName) },
        )
    }

    private fun deriveRows(engine: PolicyEngine?, history: List<PolicyAuditRecord>): List<PolicyModeRow> {
        val curated = CURATED_ROWS
        val historyClasses = history.map { it.actionClass }.distinct()
        val uncurated = historyClasses
            .filter { cls -> curated.none { it.actionClass == cls } }
            .sorted()
            .map { cls -> PolicyModeRow(cls, cls, "Seen in policy history", sampleFor(cls)) }
        return (curated + uncurated).map { row ->
            val engineOrDefault = engine ?: return@map row
            row.copy(
                effectiveMode = engineOrDefault.effectiveMode(row.sample),
                custom = engineOrDefault.hasModeOverride(row.actionClass),
                denied = engineOrDefault.isDenied(row.actionClass),
            )
        }
    }

    private fun sampleFor(actionClass: String): ProposedAction = when (actionClass) {
        "Send" -> ProposedAction.Send("")
        "SendImage" -> ProposedAction.SendImage("")
        "DeleteFile" -> ProposedAction.DeleteFile("")
        "DeleteContact" -> ProposedAction.DeleteContact("")
        "DeleteProject" -> ProposedAction.DeleteProject("")
        "ForgetFact" -> ProposedAction.ForgetFact("", "")
        "RunScript" -> ProposedAction.RunScript("")
        "PostSocialMedia" -> ProposedAction.PostSocialMedia("", "", "", "")
        "CreateEvent" -> ProposedAction.CreateEvent("", "")
        "ReplyNotification" -> ProposedAction.ReplyNotification("", "")
        "UpdateMemory" -> ProposedAction.UpdateMemory("", "")
        "OpenApp" -> ProposedAction.OpenApp("")
        // No generic "unknown" action exists in the shared contract; Tap is the
        // most benign sample, so an uncurated class still gets a sane default mode.
        else -> ProposedAction.Tap("")
    }

    companion object {
        /**
         * The curated rows — the same action classes Android's policy section
         * exposes, plus OpenApp (the desktop skills launch_app/play_media map
         * to). Classes seen in history but not curated are appended as rows so
         * every recorded class stays controllable.
         */
        val CURATED_ROWS: List<PolicyModeRow> = listOf(
            PolicyModeRow("OpenApp", "Launch apps", "Open an installed app or play media", ProposedAction.OpenApp("")),
            PolicyModeRow("Send", "Send messages", "Send a message through an app", ProposedAction.Send("")),
            PolicyModeRow("SendImage", "Send images", "Attach and send an image", ProposedAction.SendImage("")),
            PolicyModeRow("DeleteFile", "Delete files", "Permanently delete files — irreversible", ProposedAction.DeleteFile("")),
            PolicyModeRow("DeleteContact", "Delete contacts", "Delete a contact — irreversible", ProposedAction.DeleteContact("")),
            PolicyModeRow("DeleteProject", "Delete projects", "Remove a project from the tracker — irreversible", ProposedAction.DeleteProject("")),
            PolicyModeRow("ForgetFact", "Forget memory facts", "Erase a fact from memory — irreversible", ProposedAction.ForgetFact("", "")),
            PolicyModeRow("RunScript", "Run scripts", "Execute code in the sandbox", ProposedAction.RunScript("")),
            PolicyModeRow("PostSocialMedia", "Post to social media", "Publish content to social apps", ProposedAction.PostSocialMedia("", "", "", "")),
            PolicyModeRow("CreateEvent", "Create calendar events", "Add an event to your calendar", ProposedAction.CreateEvent("", "")),
            PolicyModeRow("ReplyNotification", "Reply to notifications", "Send a reply from a notification", ProposedAction.ReplyNotification("", "")),
            PolicyModeRow("UpdateMemory", "Save to memory", "Store a fact into encrypted memory", ProposedAction.UpdateMemory("", "")),
        )
    }
}
