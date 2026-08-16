package com.newax.aegis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.assistant.riskLevel
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.authority.PolicyEngine
import com.newax.aegis.authority.PolicyMode
import com.newax.aegis.ui.theme.NewaxTheme

private data class PolicyRow(
    val actionClass: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val sample: ProposedAction,
)

/**
 * The curated action classes the user can re-policy. Each row carries a
 * representative action of its class, so the effective/default modes resolve
 * through the real risk mapping (risk is per-class, so the sample is exact).
 */
private val POLICY_ROWS = listOf(
    PolicyRow("Send", R.string.policy_row_send, R.string.policy_row_send_desc, ProposedAction.Send("")),
    PolicyRow("SendImage", R.string.policy_row_send_image, R.string.policy_row_send_image_desc, ProposedAction.SendImage("")),
    PolicyRow("DeleteFile", R.string.policy_row_delete_file, R.string.policy_row_delete_file_desc, ProposedAction.DeleteFile("")),
    PolicyRow("DeleteContact", R.string.policy_row_delete_contact, R.string.policy_row_delete_contact_desc, ProposedAction.DeleteContact("")),
    PolicyRow("DeleteProject", R.string.policy_row_delete_project, R.string.policy_row_delete_project_desc, ProposedAction.DeleteProject("")),
    PolicyRow("ForgetFact", R.string.policy_row_forget_fact, R.string.policy_row_forget_fact_desc, ProposedAction.ForgetFact("", "")),
    PolicyRow("RunScript", R.string.policy_row_run_script, R.string.policy_row_run_script_desc, ProposedAction.RunScript("")),
    PolicyRow("PostSocialMedia", R.string.policy_row_post_social, R.string.policy_row_post_social_desc, ProposedAction.PostSocialMedia("", "", "", "")),
    PolicyRow("CreateEvent", R.string.policy_row_create_event, R.string.policy_row_create_event_desc, ProposedAction.CreateEvent("", "")),
    PolicyRow("ReplyNotification", R.string.policy_row_reply_notif, R.string.policy_row_reply_notif_desc, ProposedAction.ReplyNotification("", "")),
    PolicyRow("UpdateMemory", R.string.policy_row_update_memory, R.string.policy_row_update_memory_desc, ProposedAction.UpdateMemory("", "")),
)

/** Stable position of an action class among the policy rows, or null if uncurated. */
internal fun policyClassPosition(actionClass: String): Int? =
    POLICY_ROWS.indexOfFirst { it.actionClass == actionClass }.takeIf { it >= 0 }

/**
 * Policy settings — the user-controllable half of the authority spine
 * (ARCHITECTURE.md corollary: permission ≠ policy). Emitted as individual
 * LazyColumn items so the outer list can scroll precisely to one class's row
 * (the policy-history "jump to row") or to the section top (the Goals
 * "Policy modes" jump). Layout: intro card, one card per action class (effective
 * policy mode with a selector, hard-deny switch, reset — [PolicyRowCard]), then
 * the recent policy-decision audit trail with a link to the full history screen.
 * All reads/writes go through the one process engine (PolicyHolder).
 */
fun LazyListScope.policySectionItems(
    /** Bumped by [onPolicyChanged]; the audit card re-reads recent decisions on change. */
    policyVersion: Int,
    onPolicyChanged: () -> Unit,
    /** The action class whose row is highlighted after a jump from the history screen. */
    highlightedClass: String? = null,
    onOpenPolicyHistory: () -> Unit = {},
) {
    item(key = "policy-modes-header") { PolicyModesHeaderCard() }
    POLICY_ROWS.forEach { row ->
        item(key = "policy-row-${row.actionClass}") {
            PolicyRowCard(
                row = row,
                engine = PolicyHolder.engine(),
                highlighted = row.actionClass == highlightedClass,
                onChanged = onPolicyChanged
            )
        }
    }
    item(key = "policy-audit") {
        PolicyAuditCard(policyVersion = policyVersion, onOpenPolicyHistory = onOpenPolicyHistory)
    }
}

@Composable
private fun PolicyModesHeaderCard() {
    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.policy_modes_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.policy_modes_body),
                fontSize = 12.sp,
                color    = NewaxTheme.colors.textTertiary,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun PolicyRowCard(
    row: PolicyRow,
    engine: PolicyEngine,
    highlighted: Boolean = false,
    onChanged: () -> Unit
) {
    val default   = PolicyEngine.defaultModeFor(row.sample.riskLevel)
    val effective = engine.effectiveMode(row.sample)
    val denied    = engine.isDenied(row.actionClass)
    val custom    = engine.hasModeOverride(row.actionClass)

    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (highlighted) NewaxTheme.colors.surfaceMuted else NewaxTheme.colors.surface
        ),
        border    = androidx.compose.foundation.BorderStroke(
            width = if (highlighted) 2.dp else 1.dp,
            color = if (highlighted) NewaxTheme.colors.warning else NewaxTheme.colors.border
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(row.labelRes), fontWeight = FontWeight.Medium, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(row.descriptionRes), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
                }
                Spacer(Modifier.width(10.dp))
                if (denied) {
                    StatusTag(stringResource(R.string.policy_tag_denied), NewaxTheme.colors.textTertiary)
                } else if (custom) {
                    StatusTag(stringResource(R.string.policy_tag_custom, stringResource(modeLabelRes(effective))), NewaxTheme.colors.success)
                } else {
                    StatusTag(stringResource(R.string.policy_tag_default, stringResource(modeLabelRes(default))), NewaxTheme.colors.textTertiary)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PolicyMode.entries.forEach { mode ->
                    FilterChip(
                        selected = !denied && effective == mode,
                        enabled  = !denied,
                        onClick  = {
                            engine.setModeOverride(row.actionClass, mode)
                            onChanged()
                        },
                        label = { Text(stringResource(modeLabelRes(mode)), fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = denied,
                    onCheckedChange = { checked ->
                        engine.setDenied(row.actionClass, checked)
                        onChanged()
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.policy_hard_deny), fontSize = 13.sp, color = if (denied) NewaxTheme.colors.textTertiary else NewaxTheme.colors.textSecondary)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    engine.clearModeOverride(row.actionClass)
                    engine.setDenied(row.actionClass, false)
                    onChanged()
                }) {
                    Text(stringResource(R.string.action_reset_default), fontSize = 12.5.sp, color = NewaxTheme.colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun PolicyAuditCard(policyVersion: Int, onOpenPolicyHistory: () -> Unit) {
    val audits = remember(policyVersion) { PolicyHolder.recentAudits(8) }

    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.policy_recent_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenPolicyHistory) {
                    Text(stringResource(R.string.policy_see_all), fontSize = 12.5.sp, color = NewaxTheme.colors.textSecondary)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.policy_audit_note),
                fontSize = 12.sp,
                color    = NewaxTheme.colors.textTertiary
            )
            Spacer(Modifier.height(10.dp))
            if (audits.isEmpty()) {
                Text(stringResource(R.string.policy_no_decisions), fontSize = 12.5.sp, color = NewaxTheme.colors.textSecondary)
            } else {
                audits.reversed().forEach { record: PolicyAuditRecord ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(decisionColor(record.decision))
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                record.actionSummary,
                                fontSize = 12.5.sp,
                                color    = NewaxTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${record.mode.name} · ${record.decision.name} · ${record.origin.name.lowercase()}",
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 10.5.sp,
                                color      = NewaxTheme.colors.textTertiary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(NewaxTheme.colors.surfaceMuted)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

private fun modeLabelRes(mode: PolicyMode): Int = when (mode) {
    PolicyMode.AUTO               -> R.string.policy_mode_auto
    PolicyMode.CONFIGURABLE       -> R.string.policy_mode_configurable
    PolicyMode.APPROVAL           -> R.string.policy_mode_approval
    PolicyMode.STRONG_CONFIRMATION -> R.string.policy_mode_strong
}

@Composable
private fun decisionColor(decision: PolicyDecision): Color = when (decision) {
    PolicyDecision.AUTO_EXECUTE       -> NewaxTheme.colors.success
    PolicyDecision.REQUIRE_APPROVAL   -> NewaxTheme.colors.warning
    PolicyDecision.REQUIRE_STRONG     -> NewaxTheme.colors.error
    PolicyDecision.DENY               -> NewaxTheme.colors.textTertiary
}
