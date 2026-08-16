package com.newax.aegis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newax.aegis.assistant.ChatMessage
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.assistant.riskLevel
import com.newax.aegis.authority.PolicyMode
import com.newax.aegis.ui.a11y.liveRegionAssertive
import com.newax.aegis.ui.a11y.liveRegionPolite
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.components.ChoiceChips
import com.newax.aegis.ui.components.ListeningIndicator
import com.newax.aegis.ui.components.ModelStatusLine
import com.newax.aegis.ui.components.Sheet
import com.newax.aegis.ui.components.StatusChip
import com.newax.aegis.ui.components.TranscriptPreview
import com.newax.aegis.ui.risk.riskBadgeStyle
import com.newax.aegis.ui.state.ChatExportFormat
import com.newax.aegis.ui.state.ChatExportState
import com.newax.aegis.ui.state.VoiceCapturePhase
import com.newax.aegis.ui.state.VoiceCaptureState
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * The chat-surface overlays (T3.5b) — routes 1.4 (model sheet), 1.9 (step
 * detail) and 1.12 (export conversation). Each is a shared [Sheet] whose
 * content resolves localized strings and platform actions; the shared
 * components own layout, semantics and the 44 dp floors.
 *
 * The overlays are rendered by the app shell ([NewaxApp]) so they sit above
 * the whole surface; [ChatScreen] triggers them through callbacks.
 */

/**
 * The model state chip word + colour (thread line 1.2 and model sheet 1.4) —
 * one definition so the two surfaces cannot disagree. Busy beats ready beats
 * unloaded; every colour pairs with a contrast-verified token (ContrastTest).
 */
internal data class ModelStateChip(val labelRes: Int, val color: Color)

@Composable
internal fun modelStateChip(vm: MainViewModel): ModelStateChip {
    val colors = NewaxTheme.colors
    return when {
        vm.modelBusy -> ModelStateChip(R.string.model_state_busy, colors.textSecondary)
        vm.modelReady -> ModelStateChip(R.string.model_state_ready, colors.success)
        vm.modelName.isNotBlank() -> ModelStateChip(R.string.model_state_unloaded, colors.warning)
        else -> ModelStateChip(R.string.model_state_not_installed, colors.textTertiary)
    }
}

// ── 1.4 Model sheet ───────────────────────────────────────────────────────────
/**
 * Route 1.4 — the model sheet: the current model + its honest state, the
 * SHA-256 identity, Import / Reload / Unload, and the jump to the full model
 * settings (5.2.1 — today the Settings screen). Unload returns to basic mode
 * but keeps the imported file on disk, so Reload brings it back; the status
 * line says exactly that ([R.string.model_unloaded]).
 */
@Composable
fun ModelSheet(
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onAllModelSettings: () -> Unit,
) {
    val hasModel = vm.modelName.isNotBlank()
    val chip = modelStateChip(vm)
    Sheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.model_sheet_title),
        closeLabel = stringResource(R.string.cd_close),
        onClose = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm)) {
            if (hasModel) {
                ModelStatusLine(
                    modelName = vm.modelName,
                    stateLabel = stringResource(chip.labelRes),
                    stateColor = chip.color,
                )
                // The full status line is engine-produced content (the ready /
                // unloaded / failed message), shown as detail under the chip.
                Text(
                    vm.modelStatus,
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.textSecondary,
                )
                Text(
                    stringResource(R.string.model_sheet_sha, vm.modelSha256),
                    style = NewaxTheme.typography.mono,
                    color = NewaxTheme.colors.textTertiary,
                )
            } else {
                Text(
                    stringResource(R.string.model_sheet_none),
                    style = NewaxTheme.typography.body,
                    color = NewaxTheme.colors.textSecondary,
                )
            }
            Spacer(Modifier.height(NewaxTheme.spacing.md))
            Button(
                onClick = onImport,
                enabled = !vm.modelBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NewaxTheme.colors.textPrimary,
                    contentColor = NewaxTheme.colors.surface,
                ),
            ) { Text(stringResource(R.string.model_sheet_import), style = NewaxTheme.typography.label) }
            Row(horizontalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm)) {
                TextButton(
                    onClick = vm::reloadModel,
                    enabled = hasModel && !vm.modelBusy,
                ) { Text(stringResource(R.string.model_sheet_reload), style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary) }
                TextButton(
                    onClick = vm::unloadModel,
                    enabled = hasModel && !vm.modelBusy,
                ) { Text(stringResource(R.string.model_sheet_unload), style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary) }
            }
            TextButton(
                onClick = onAllModelSettings,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.model_sheet_all_settings), style = NewaxTheme.typography.label) }
        }
    }
}

// ── 1.9 Step detail ───────────────────────────────────────────────────────────
/** The step-detail sheet's rule line, for the pending action (route 1.9). */
private fun policyModeLabelRes(mode: PolicyMode): Int = when (mode) {
    PolicyMode.AUTO -> R.string.policy_mode_auto
    PolicyMode.CONFIGURABLE -> R.string.policy_mode_configurable
    PolicyMode.APPROVAL -> R.string.policy_mode_approval
    PolicyMode.STRONG_CONFIRMATION -> R.string.policy_mode_strong
}

/**
 * Route 1.9 — step detail for the action currently awaiting approval: what
 * the action is, its risk class (the canonical [RiskLevel] chip, never a
 * re-derived vocabulary), the required gate, and the policy rule that governs
 * it — read from the one engine ([PolicyHolder]) so the sheet cannot disagree
 * with the spine. Audit link (5.3.1.3) and the rule-editing link (5.3.1.1)
 * resolve to the screens that exist today.
 */
@Composable
fun StepDetailSheet(
    action: ProposedAction,
    onDismiss: () -> Unit,
    onSeeInHistory: () -> Unit,
    onChangePolicy: () -> Unit,
) {
    val engine = PolicyHolder.engineOrNull()
    val actionClass = action::class.simpleName.orEmpty()
    val mode = engine?.effectiveMode(action)
    val riskStyle = riskBadgeStyle(action.riskLevel, NewaxTheme.colors)
    val modeLabel = if (mode != null) stringResource(policyModeLabelRes(mode)) else null
    val ruleText = when {
        engine?.isDenied(actionClass) == true -> stringResource(R.string.step_rule_denied)
        modeLabel != null && engine?.modeOverride(actionClass) != null ->
            stringResource(R.string.step_rule_override, modeLabel)
        modeLabel != null -> stringResource(R.string.step_rule_default, modeLabel)
        else -> stringResource(R.string.step_rule_default, stringResource(R.string.policy_mode_auto))
    }
    Sheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.step_detail_title),
        closeLabel = stringResource(R.string.cd_close),
        onClose = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.md)) {
            DetailRow(stringResource(R.string.step_detail_action), action.summary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.step_detail_risk),
                    style = NewaxTheme.typography.label,
                    color = NewaxTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    stringResource(riskStyle.labelRes),
                    riskStyle.foreground,
                    fill = riskStyle.background,
                )
            }
            if (modeLabel != null) {
                DetailRow(stringResource(R.string.step_detail_gate), modeLabel)
            }
            DetailRow(stringResource(R.string.step_detail_rule), ruleText)
            Row(horizontalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm)) {
                TextButton(
                    onClick = onSeeInHistory,
                ) { Text(stringResource(R.string.step_detail_see_history), style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary) }
                TextButton(
                    onClick = onChangePolicy,
                ) { Text(stringResource(R.string.step_detail_change_rule), style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary) }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = NewaxTheme.typography.label,
            color = NewaxTheme.colors.textTertiary,
        )
        Text(
            value,
            style = NewaxTheme.typography.body,
            color = NewaxTheme.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── 1.12 Export conversation ──────────────────────────────────────────────────
/** The export's outcome, owned by the app shell (the write is a platform seam). */
sealed interface ExportStatus {
    data object Idle : ExportStatus
    data class Done(val fileName: String) : ExportStatus
    data class Failed(val reason: String) : ExportStatus
}

private fun exportFormatLabelRes(format: ChatExportFormat): Int = when (format) {
    ChatExportFormat.MARKDOWN -> R.string.export_format_markdown
    ChatExportFormat.TEXT -> R.string.export_format_text
    ChatExportFormat.JSON -> R.string.export_format_json
}

/**
 * Route 1.12 — the export sheet: format choice (Markdown / Text / JSON),
 * message count, and Export. Rendering and file naming are pure
 * ([ChatExportState], unit-tested); the write goes through the caller
 * ([onExport]) — the SAF picker is a platform seam this file does not touch.
 * An empty transcript is an honest "nothing to export", not an empty file;
 * the outcome ([status]) announces politely on success and assertively on
 * failure.
 */
@Composable
fun ExportSheet(
    messages: List<ChatMessage>,
    title: String,
    status: ExportStatus,
    onDismiss: () -> Unit,
    onExport: (text: String, format: ChatExportFormat, suggestedName: String) -> Unit,
) {
    val state = remember { ChatExportState() }
    val formats = remember { ChatExportFormat.entries }
    var format by remember { mutableStateOf(ChatExportFormat.MARKDOWN) }
    // Resolved once, outside any lambda: stringResource is composable-only and
    // cannot be called from the ChoiceChips callbacks below.
    val formatOptions = formats.map { format -> stringResource(exportFormatLabelRes(format)) to format }
    Sheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.export_sheet_title),
        closeLabel = stringResource(R.string.cd_close),
        onClose = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.md)) {
            if (messages.isEmpty()) {
                Text(
                    stringResource(R.string.export_sheet_empty),
                    style = NewaxTheme.typography.body,
                    color = NewaxTheme.colors.textSecondary,
                )
                return@Column
            }
            Text(
                stringResource(R.string.export_sheet_messages, messages.size),
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.textTertiary,
            )
            Text(
                stringResource(R.string.export_sheet_format),
                style = NewaxTheme.typography.label,
                color = NewaxTheme.colors.textSecondary,
            )
            ChoiceChips(
                options = formatOptions.map { it.first },
                selected = formatOptions.first { it.second == format }.first,
                onSelect = { label -> format = formatOptions.first { it.first == label }.second },
            )
            Button(
                onClick = {
                    val rendered = state.render(messages, format, title = title)
                    val name = state.exportFileName(title, format, System.currentTimeMillis())
                    onExport(rendered, format, name)
                },
                enabled = status !is ExportStatus.Done,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NewaxTheme.colors.textPrimary,
                    contentColor = NewaxTheme.colors.surface,
                ),
            ) { Text(stringResource(R.string.export_sheet_export), style = NewaxTheme.typography.label) }
            when (status) {
                is ExportStatus.Idle -> Unit
                is ExportStatus.Done -> Text(
                    stringResource(R.string.export_done, status.fileName),
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.success,
                    modifier = Modifier.liveRegionPolite(),
                )
                is ExportStatus.Failed -> Text(
                    stringResource(R.string.export_failed, status.reason),
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.error,
                    modifier = Modifier.liveRegionAssertive(),
                )
            }
        }
    }
}

// ── 1.10 Voice capture ───────────────────────────────────────────────────────
/**
 * Route 1.10 — voice capture: the live level meter, the running transcript,
 * Stop (inserts into the composer and returns to 1.2) and Cancel (discards).
 * The composer's mic opens this sheet instead of the one-shot system
 * recognizer, because the meter needs `onRmsChanged` and the running
 * transcript needs `onPartialResults` — neither exists on the
 * `RecognizerIntent` activity (T3.5c).
 *
 * The level meter is decorative: the transcript is the accessible
 * representation and is a polite live region (docs/UI_DESIGN.md §6.3). Both
 * shared components ([ListeningIndicator], [TranscriptPreview]) already ship
 * that contract — their 1.10 route lands here.
 *
 * [VoiceCaptureState] drives the phases; [VoiceCaptureSession] (the
 * recognizer seam) lives in the caller. Stop with a transcript hands it to
 * the caller ([onStop] re-reads it from the state); Stop with nothing heard
 * moves the sheet to the nothing-recognized error instead of closing
 * silently.
 */
@Composable
fun VoiceCaptureSheet(
    state: VoiceCaptureState,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    Sheet(
        onDismiss = onCancel,
        title = stringResource(R.string.voice_capture_title),
        closeLabel = stringResource(R.string.cd_close),
        onClose = onCancel,
    ) {
        when (state.phase) {
            VoiceCapturePhase.IDLE -> Text(
                stringResource(R.string.voice_starting),
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.textSecondary,
                modifier = Modifier.liveRegionPolite(),
            )

            VoiceCapturePhase.LISTENING -> {
                ListeningIndicator(
                    amplitude = state.amplitude,
                    label = stringResource(R.string.voice_listening),
                    description = stringResource(R.string.voice_listening_description),
                )
                Spacer(Modifier.height(NewaxTheme.spacing.md))
                TranscriptPreview(
                    text = state.partialText,
                    placeholder = stringResource(R.string.voice_transcript_placeholder),
                )
                Spacer(Modifier.height(NewaxTheme.spacing.md))
                VoiceCaptureActions(onStop = onStop, onCancel = onCancel)
            }

            VoiceCapturePhase.DONE -> {
                // The recognizer finished on its own (silence): the final
                // transcript is shown; Stop inserts it, Cancel discards it.
                TranscriptPreview(text = state.finalText.orEmpty())
                Spacer(Modifier.height(NewaxTheme.spacing.md))
                VoiceCaptureActions(onStop = onStop, onCancel = onCancel)
            }

            VoiceCapturePhase.ERROR -> {
                Text(
                    stringResource(state.errorLabelRes ?: R.string.voice_error_generic),
                    style = NewaxTheme.typography.body,
                    color = NewaxTheme.colors.error,
                    modifier = Modifier.liveRegionAssertive(),
                )
                Spacer(Modifier.height(NewaxTheme.spacing.md))
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.minimumTouchTarget(),
                ) { Text(stringResource(R.string.action_cancel), style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary) }
            }
        }
    }
}

@Composable
private fun VoiceCaptureActions(onStop: () -> Unit, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm)) {
        Button(
            onClick = onStop,
            modifier = Modifier.minimumTouchTarget(),
            colors = ButtonDefaults.buttonColors(
                containerColor = NewaxTheme.colors.textPrimary,
                contentColor = NewaxTheme.colors.surface,
            ),
        ) { Text(stringResource(R.string.action_stop), style = NewaxTheme.typography.label) }
        TextButton(
            onClick = onCancel,
            modifier = Modifier.minimumTouchTarget(),
        ) { Text(stringResource(R.string.action_cancel), style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary) }
    }
}
