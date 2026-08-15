package com.newax.aegis.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.assistant.riskLevel
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.authority.PolicyEngine
import com.newax.aegis.authority.PolicyMode
import com.newax.aegis.desktop.ActionClassStat
import com.newax.aegis.desktop.ui.state.ExportState
import com.newax.aegis.desktop.ui.state.PolicyModeRow
import com.newax.aegis.desktop.ui.state.PolicyScreenState
import com.newax.aegis.desktop.ui.state.PolicyUiModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.newax.aegis.ui.theme.NewaxLightColors

/**
 * Policy tab — the desktop face of the authority spine (mirroring Android's
 * policy settings section + policy history screen in one place). Per-action-class
 * effective policy mode with mode selector, hard-deny switch, and reset; the
 * policy-decision audit trail with a decision filter; per-decision summary
 * stats; and the per-class approval-pressure breakdown ("which actions need the
 * most approvals"). All reads/writes go through the one process engine
 * ([DesktopPolicyHolder]); the state holder [PolicyScreenState] is the
 * plain-Kotlin testable core.
 */
@Composable
fun PolicyScreen(state: PolicyScreenState) {
    val model by state.model.collectAsState()
    val filter by state.decisionFilter.collectAsState()
    val exportState by state.exportState.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    val fmt = remember { POLICY_TIME_FORMATTER }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        when (val current = model) {
            is PolicyUiModel.Loading -> item {
                EmptyState("Loading policy…", null, iconColor = WarningColor)
            }
            is PolicyUiModel.Error -> item {
                EmptyState("Could not load policy", current.message, iconColor = ErrorColor)
            }
            is PolicyUiModel.Content -> {
                // ── Summary: decisions + approval pressure + export ───────
                item {
                    PolicySummaryCard(
                        model = current,
                        exportState = exportState,
                        onRefresh = { state.refresh() },
                        onExport = { state.export() },
                    )
                }

                // ── Policy modes: one row per action class ────────────────
                item {
                    SectionTitle("Policy modes", "Which actions run silently, which ask — per class")
                }
                items(current.rows, key = { "policy-${it.actionClass}" }) { row ->
                    PolicyModeCard(
                        row = row,
                        onModeChange = { mode -> state.setMode(row.actionClass, mode) },
                        onDenyChange = { denied -> state.setDenied(row.actionClass, denied) },
                        onReset = { state.reset(row.actionClass) },
                    )
                }

                // ── Decision history ──────────────────────────────────────
                item {
                    SectionTitle(
                        "Decision history",
                        if (current.records.isEmpty()) "Every policy evaluation appears here"
                        else "${current.records.size} decisions recorded across sessions"
                    )
                }
                if (current.records.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = filter == null,
                                onClick = { state.setDecisionFilter(null) },
                                label = { Text("All", fontSize = 12.sp) }
                            )
                            PolicyDecision.entries.forEach { decision ->
                                FilterChip(
                                    selected = filter == decision,
                                    onClick = { state.setDecisionFilter(decision) },
                                    label = { Text(decisionLabel(decision), fontSize = 12.sp) }
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(
                                onClick = { showClearDialog = true },
                                enabled = current.records.isNotEmpty(),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (current.filteredRecords.isEmpty()) {
                        item {
                            EmptyState(
                                "No ${decisionLabel(filter ?: PolicyDecision.AUTO_EXECUTE).lowercase()} decisions",
                                "Try a different filter — nothing in the trail matches this one.",
                                iconColor = TextTertiaryColor
                            )
                        }
                    } else {
                        items(current.filteredRecords, key = { "${it.actionClass}-${it.auditedAtMs}-${it.decision.name}" }) { record ->
                            PolicyAuditCard(record, fmt)
                        }
                    }
                } else {
                    item {
                        EmptyState(
                            "No policy decisions yet",
                            "Run a goal with a policy-gated skill (launch an app, send a message, set a reminder) to start the trail — every evaluation is recorded here across sessions.",
                            iconColor = TextTertiaryColor
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear policy history?") },
            text = {
                Text(
                    "Every recorded policy decision will be erased from this device. Policy modes themselves are not affected.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.clear()
                    showClearDialog = false
                }) { Text("Clear", color = ErrorColor, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimaryColor)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, fontSize = 12.sp, color = TextTertiaryColor)
    }
}

@Composable
private fun PolicySummaryCard(
    model: PolicyUiModel.Content,
    exportState: ExportState,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (model.records.isEmpty()) "No policy decisions"
                        else "${model.records.size} ${if (model.records.size == 1) "decision" else "decisions"}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = TextPrimaryColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Recorded across sessions — who asked, what was decided",
                        fontSize = 12.sp,
                        color = TextTertiaryColor
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh policy", tint = TextSecondaryColor)
                }
            }
            if (model.records.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Follows the active decision filter (the state holder's contract).
                    PolicyDecision.entries.forEach { decision ->
                        val count = when (decision) {
                            PolicyDecision.AUTO_EXECUTE -> model.summary.autoExecuted
                            PolicyDecision.REQUIRE_APPROVAL -> model.summary.approvals
                            PolicyDecision.REQUIRE_STRONG -> model.summary.strong
                            PolicyDecision.DENY -> model.summary.denied
                        }
                        DecisionStat(decisionLabel(decision), count, decisionColor(decision))
                    }
                }

                if (model.breakdown.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = BorderColor)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Actions needing most approvals",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextTertiaryColor
                    )
                    Spacer(Modifier.height(4.dp))
                    model.breakdown.take(5).forEach { stat ->
                        ActionClassRow(stat)
                    }
                    if (model.breakdown.size > 5) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "+ ${model.breakdown.size - 5} more action classes",
                            fontSize = 11.sp,
                            color = TextTertiaryColor
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onExport,
                        enabled = model.records.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TextPrimaryColor,
                            contentColor = SurfaceColor,
                            disabledContainerColor = SurfaceMutedColor,
                            disabledContentColor = TextTertiaryColor
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export CSV", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(12.dp))
                    ExportStatusLine(exportState, "Exports land in ~/.aegis/policy-audit-<timestamp>.csv")
                }
            }
        }
    }
}

@Composable
private fun DecisionStat(label: String, count: Int, color: Color) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceMutedColor)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            count.toString(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) color else TextTertiaryColor,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = TextTertiaryColor)
    }
}

/** One action class's share of approval pressure — amber when it prompts, green when automatic. */
@Composable
private fun ActionClassRow(stat: ActionClassStat) {
    val prompts = stat.needsHuman > 0
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (prompts) WarningColor else ReadyColor)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stat.actionClass,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimaryColor,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
        Text(
            buildString {
                append("${stat.needsHuman} of ${stat.total} need approval")
                if (stat.denied > 0) append(" · ${stat.denied} denied")
            },
            fontSize = 12.sp,
            color = if (prompts) WarningColor else ReadyColor
        )
    }
}

@Composable
private fun PolicyModeCard(
    row: PolicyModeRow,
    onModeChange: (PolicyMode) -> Unit,
    onDenyChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val default = PolicyEngine.defaultModeFor(row.sample.riskLevel)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, if (row.denied) DenyColor else BorderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.label, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimaryColor)
                    Spacer(Modifier.height(2.dp))
                    Text(row.description, fontSize = 12.sp, color = TextTertiaryColor)
                }
                Spacer(Modifier.width(10.dp))
                when {
                    row.denied -> Tag("Denied", DenyColor)
                    row.custom -> Tag("Custom: ${modeLabel(row.effectiveMode)}", ReadyColor)
                    else -> Tag("Default: ${modeLabel(default)}", TextTertiaryColor)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PolicyMode.entries.forEach { mode ->
                    FilterChip(
                        selected = !row.denied && row.effectiveMode == mode,
                        enabled = !row.denied,
                        onClick = { onModeChange(mode) },
                        label = { Text(modeLabel(mode), fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = row.denied,
                    onCheckedChange = onDenyChange
                )
                Spacer(Modifier.width(8.dp))
                Text("Hard deny", fontSize = 13.sp, color = if (row.denied) DenyColor else TextSecondaryColor)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onReset,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextTertiaryColor),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) {
                    Text("Reset to default", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PolicyAuditCard(record: PolicyAuditRecord, fmt: DateTimeFormatter) {
    val color = decisionColor(record.decision)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        record.actionClass,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimaryColor,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        record.actionSummary,
                        fontSize = 12.sp,
                        color = TextSecondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${modeLabel(record.mode)} · ${record.origin.name.lowercase()} · ${record.reason}",
                    fontSize = 11.5.sp,
                    color = TextTertiaryColor,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Tag(decisionLabel(record.decision), color)
                Spacer(Modifier.height(3.dp))
                Text(
                    fmt.format(Instant.ofEpochMilli(record.auditedAtMs).atZone(ZoneId.systemDefault())),
                    fontSize = 10.5.sp,
                    color = TextTertiaryColor,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ── Labels & colors (shared with the audit decision rendering) ─────────────
private val DenyColor = NewaxLightColors.textTertiary
private val AutoColor = ReadyColor

private fun modeLabel(mode: PolicyMode): String = when (mode) {
    PolicyMode.AUTO -> "Auto"
    PolicyMode.CONFIGURABLE -> "Configurable"
    PolicyMode.APPROVAL -> "Approval"
    PolicyMode.STRONG_CONFIRMATION -> "Strong"
}

private fun decisionLabel(decision: PolicyDecision): String = when (decision) {
    PolicyDecision.AUTO_EXECUTE -> "Auto"
    PolicyDecision.REQUIRE_APPROVAL -> "Approval"
    PolicyDecision.REQUIRE_STRONG -> "Strong"
    PolicyDecision.DENY -> "Denied"
}

private fun decisionColor(decision: PolicyDecision): Color = when (decision) {
    PolicyDecision.AUTO_EXECUTE -> AutoColor
    PolicyDecision.REQUIRE_APPROVAL -> WarningColor
    PolicyDecision.REQUIRE_STRONG -> ErrorColor
    PolicyDecision.DENY -> DenyColor
}

private val POLICY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d · HH:mm")
