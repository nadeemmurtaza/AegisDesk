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

// ── Design tokens (match CapabilitiesScreen / REFINED_THEME.md) ─────────────
private val Surface      = Color(0xFFFFFFFF)
private val SurfaceMuted = Color(0xFFF2F2EF)
private val TextPri      = Color(0xFF1B1B1A)
private val TextSec      = Color(0xFF686864)
private val TextTer      = Color(0xFF8D8D87)
private val Border       = Color(0xFFD8D8D3)
private val WarnCol      = Color(0xFFF59E0B)
private val AutoCol      = Color(0xFF22C55E)
private val ApprovalCol  = Color(0xFFF59E0B)
private val StrongCol    = Color(0xFFEF4444)
private val DenyCol      = Color(0xFF64748B)

private data class PolicyRow(
    val actionClass: String,
    val label: String,
    val description: String,
    val sample: ProposedAction,
)

/**
 * The curated action classes the user can re-policy. Each row carries a
 * representative action of its class, so the effective/default modes resolve
 * through the real risk mapping (risk is per-class, so the sample is exact).
 */
private val POLICY_ROWS = listOf(
    PolicyRow("Send", "Send messages", "Send a message through an app", ProposedAction.Send("")),
    PolicyRow("SendImage", "Send images", "Attach and send an image", ProposedAction.SendImage("")),
    PolicyRow("DeleteFile", "Delete files", "Permanently delete files — irreversible", ProposedAction.DeleteFile("")),
    PolicyRow("DeleteContact", "Delete contacts", "Delete a contact — irreversible", ProposedAction.DeleteContact("")),
    PolicyRow("DeleteProject", "Delete projects", "Remove a project from the tracker — irreversible", ProposedAction.DeleteProject("")),
    PolicyRow("ForgetFact", "Forget memory facts", "Erase a fact from memory — irreversible", ProposedAction.ForgetFact("", "")),
    PolicyRow("RunScript", "Run scripts", "Execute code in the sandbox", ProposedAction.RunScript("")),
    PolicyRow("PostSocialMedia", "Post to social media", "Publish content to social apps", ProposedAction.PostSocialMedia("", "", "", "")),
    PolicyRow("CreateEvent", "Create calendar events", "Add an event to your calendar", ProposedAction.CreateEvent("", "")),
    PolicyRow("ReplyNotification", "Reply to notifications", "Send a reply from a notification", ProposedAction.ReplyNotification("", "")),
    PolicyRow("UpdateMemory", "Save to memory", "Store a fact into encrypted memory", ProposedAction.UpdateMemory("", "")),
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
        colors    = CardDefaults.cardColors(containerColor = Surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Policy modes", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri)
            Spacer(Modifier.height(4.dp))
            Text(
                "What Aegis may do automatically per action class. The mode is the policy " +
                    "answer (permission is the OS answer); the mapping is user-controllable " +
                    "and persists encrypted. Auto / Configurable / Approval / Strong confirmation.",
                fontSize = 12.sp,
                color    = TextTer,
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
            containerColor = if (highlighted) SurfaceMuted else Surface
        ),
        border    = androidx.compose.foundation.BorderStroke(
            width = if (highlighted) 2.dp else 1.dp,
            color = if (highlighted) WarnCol else Border
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.label, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPri)
                    Spacer(Modifier.height(2.dp))
                    Text(row.description, fontSize = 12.sp, color = TextSec)
                }
                Spacer(Modifier.width(10.dp))
                if (denied) {
                    StatusTag("Denied", DenyCol)
                } else if (custom) {
                    StatusTag("Custom: ${modeLabel(effective)}", AutoCol)
                } else {
                    StatusTag("Default: ${modeLabel(default)}", TextTer)
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
                        label = { Text(modeLabel(mode), fontSize = 12.sp) }
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
                Text("Hard deny", fontSize = 13.sp, color = if (denied) DenyCol else TextSec)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    engine.clearModeOverride(row.actionClass)
                    engine.setDenied(row.actionClass, false)
                    onChanged()
                }) {
                    Text("Reset to default", fontSize = 12.5.sp, color = TextSec)
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
        colors    = CardDefaults.cardColors(containerColor = Surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent policy decisions", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenPolicyHistory) {
                    Text("See all", fontSize = 12.5.sp, color = TextSec)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Every evaluation is audited — who asked, what was decided (RULE 8).",
                fontSize = 12.sp,
                color    = TextTer
            )
            Spacer(Modifier.height(10.dp))
            if (audits.isEmpty()) {
                Text("No policy decisions recorded yet.", fontSize = 12.5.sp, color = TextSec)
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
                                color    = TextPri,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${record.mode.name} · ${record.decision.name} · ${record.origin.name.lowercase()}",
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 10.5.sp,
                                color      = TextTer
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
            .background(SurfaceMuted)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

private fun modeLabel(mode: PolicyMode): String = when (mode) {
    PolicyMode.AUTO               -> "Auto"
    PolicyMode.CONFIGURABLE       -> "Configurable"
    PolicyMode.APPROVAL           -> "Approval"
    PolicyMode.STRONG_CONFIRMATION -> "Strong"
}

private fun decisionColor(decision: PolicyDecision): Color = when (decision) {
    PolicyDecision.AUTO_EXECUTE       -> AutoCol
    PolicyDecision.REQUIRE_APPROVAL   -> ApprovalCol
    PolicyDecision.REQUIRE_STRONG     -> StrongCol
    PolicyDecision.DENY               -> DenyCol
}
