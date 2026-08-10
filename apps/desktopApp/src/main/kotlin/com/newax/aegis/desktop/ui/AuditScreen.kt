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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.desktop.AuditSummary
import com.newax.aegis.desktop.ExecutionAuditEntry
import com.newax.aegis.desktop.ui.state.AuditScreenState
import com.newax.aegis.desktop.ui.state.AuditUiModel
import com.newax.aegis.desktop.ui.state.ExportState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Audit tab — the desktop face of the execution audit trail (the "Recent runs"
 * block of `printGoals` lifted into a full screen, mirroring Android's A8
 * audit surface). Shows every recorded run, newest first — outcome, launch
 * tiers used, task count, window, and failure reason — with a CSV export that
 * writes `audit-<timestamp>.csv` under `~/.aegis/` and surfaces the path (or
 * an honest failure) in the summary card.
 */
@Composable
fun AuditScreen(state: AuditScreenState) {
    val model by state.model.collectAsState()
    val exportState by state.exportState.collectAsState()

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        when (val current = model) {
            is AuditUiModel.Loading -> item {
                EmptyState("Loading audit trail…", null, iconColor = WarningColor)
            }
            is AuditUiModel.Error -> item {
                EmptyState("Could not load the audit trail", current.message, iconColor = ErrorColor)
            }
            is AuditUiModel.Content -> {
                if (current.entries.isEmpty()) {
                    item {
                        EmptyState(
                            "No runs recorded yet",
                            "Run a goal to build the audit trail — every execution is logged here with its outcome and launch tier.",
                        )
                    }
                } else {
                    item {
                        AuditSummaryCard(
                            summary = current.summary,
                            exportState = exportState,
                            canExport = true,
                            onRefresh = { state.refresh() },
                            onExport = { state.export() },
                        )
                    }
                    items(current.entries, key = { "${it.goalId}-${it.startedMs}-${it.completedMs}" }) { entry ->
                        AuditEntryCard(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditSummaryCard(
    summary: AuditSummary,
    exportState: ExportState,
    canExport: Boolean,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Execution audit",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = TextPrimaryColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Every goal execution, audited with its outcome and launch tier — export the full trail as CSV.",
                        fontSize = 12.sp,
                        color = TextTertiaryColor,
                        lineHeight = 17.sp
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh audit trail", tint = TextSecondaryColor)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SummaryStat("Runs", summary.totalRuns.toString())
                SummaryStat(
                    "Success rate",
                    "${summary.successRatePercent}%",
                    color = successRateColor(summary.successRatePercent)
                )
                SummaryStat("Avg duration", formatDurationMs(summary.avgDurationMs))
                SummaryStat("Blocked", summary.blockedRuns.toString())
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onExport,
                    enabled = canExport,
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
                ExportStatusLine(exportState)
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, color: Color = TextPrimaryColor) {
    Column {
        Text(
            value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = TextTertiaryColor)
    }
}

/** Success-rate traffic light — green above 80%, amber above 50%, red below. */
private fun successRateColor(percent: Int): Color = when {
    percent >= 80 -> ReadyColor
    percent >= 50 -> WarningColor
    else -> ErrorColor
}

@Composable
private fun ExportStatusLine(state: ExportState) {
    when (state) {
        is ExportState.Idle -> Text(
            "Exports land in ~/.aegis/audit-<timestamp>.csv",
            fontSize = 12.sp,
            color = TextTertiaryColor
        )
        is ExportState.Done -> Text(
            "Exported → $state.path",
            fontSize = 12.sp,
            color = ReadyColor,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        is ExportState.Failed -> Text(
            state.message,
            fontSize = 12.sp,
            color = ErrorColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AuditEntryCard(entry: ExecutionAuditEntry) {
    val ok = entry.outcome == "COMPLETED"
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, if (ok) BorderColor else ErrorColor.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // ── Headline: dot · goal · outcome chip ───────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) ReadyColor else ErrorColor))
                Spacer(Modifier.width(10.dp))
                Text(
                    entry.goalDescription,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = TextPrimaryColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(entry.outcome, if (ok) ReadyColor else ErrorColor)
            }

            // ── Meta: tiers · tasks · window · duration ───────────────────
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    if (entry.tiers.isNotEmpty()) append(entry.tiers.joinToString(" · ")).append("  ·  ")
                    append("${entry.taskCount} ${if (entry.taskCount == 1) "task" else "tasks"}").append("  ·  ")
                    append(formatAuditTime(entry.completedMs))
                    if (entry.durationMs > 0) append("  ·  ${entry.durationMs} ms")
                },
                fontSize = 11.5.sp,
                color = TextTertiaryColor,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )

            // ── Reason (blocked runs show why) ────────────────────────────
            entry.reason?.let { reason ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "✗ $reason",
                    fontSize = 12.sp,
                    color = ErrorColor,
                    lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private val AUDIT_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun formatAuditTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(AUDIT_TIME_FORMATTER)
