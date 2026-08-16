package com.newax.aegis

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.engine.audit.ActionClassStat
import com.newax.aegis.engine.audit.PolicyCsv
import com.newax.aegis.engine.audit.actionClassBreakdown
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.newax.aegis.ui.theme.NewaxTheme

@Composable
private fun decisionColor(decision: PolicyDecision): Color = when (decision) {
    PolicyDecision.AUTO_EXECUTE     -> NewaxTheme.colors.success
    PolicyDecision.REQUIRE_APPROVAL -> NewaxTheme.colors.warning
    PolicyDecision.REQUIRE_STRONG   -> NewaxTheme.colors.error
    PolicyDecision.DENY             -> NewaxTheme.colors.textTertiary
}

private fun decisionLabelRes(decision: PolicyDecision): Int = when (decision) {
    PolicyDecision.AUTO_EXECUTE     -> R.string.policy_decision_auto
    PolicyDecision.REQUIRE_APPROVAL -> R.string.policy_decision_approval
    PolicyDecision.REQUIRE_STRONG   -> R.string.policy_decision_strong
    PolicyDecision.DENY             -> R.string.policy_decision_denied
}

/** Where the last CSV export attempt ended: nothing yet, saved, or failed with a reason. */
private sealed interface ExportStatus {
    data object Idle : ExportStatus
    data object Done : ExportStatus
    data class Failed(val message: String) : ExportStatus
}

private val CSV_TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())

private fun csvTimestamp(): String = CSV_TIMESTAMP_FORMAT.format(Date())

/**
 * Policy-decision history — the full audit trail across sessions (RULE 8: who
 * asked, what was decided). Read from the persistent [PolicyHolder.auditHistory]:
 * every evaluation the engine recorded, newest first, with per-decision summary
 * stats, a decision filter, and a clear-with-confirmation action.
 */
@Composable
fun PolicyHistoryScreen(padding: PaddingValues, onOpenActionClass: (String) -> Unit = {}) {
    var version by remember { mutableIntStateOf(0) }
    val records = remember(version) { PolicyHolder.auditHistory() }
    var filter by remember { mutableStateOf<PolicyDecision?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<ExportStatus>(ExportStatus.Idle) }
    var shareUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val dateFormatPattern = stringResource(R.string.policy_date_format)
    val fmt = remember(dateFormatPattern) { SimpleDateFormat(dateFormatPattern, Locale.getDefault()) }
    // Resolved here, outside the (non-composable) share lambda (T3.2b).
    val shareSubject = stringResource(R.string.policy_share_subject)
    val shareChooser = stringResource(R.string.policy_share_chooser)

    val filtered = if (filter == null) records else records.filter { it.decision == filter }

    // SAF create-document: the user picks where the CSV lands (no storage permission needed).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // user cancelled the picker
        val csv = PolicyCsv.csv(filtered.sortedByDescending { it.auditedAtMs })
        val bytes = csv.toByteArray(Charsets.UTF_8)
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
            } ?: throw IllegalStateException("Could not open the selected file")
            exportStatus = ExportStatus.Done
        } catch (e: Exception) {
            exportStatus = ExportStatus.Failed(e.message ?: e.javaClass.simpleName)
            return@rememberLauncherForActivityResult
        }
        // Share copy in app cache, exposed through the manifest FileProvider —
        // guaranteed readable by email apps, unlike re-sharing the SAF-picked
        // URI (provider-dependent). Best effort: Share appears only if this works.
        shareUri = runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "policy-audit-${csvTimestamp()}.csv")
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    val shareExport: () -> Unit = {
        val uri = shareUri
        if (uri != null) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(shareSubject, uri)
            }
            context.startActivity(Intent.createChooser(send, shareChooser))
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // ── Summary ────────────────────────────────────────────────────────
        item {
            Card(
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (records.isEmpty()) stringResource(R.string.policy_summary_empty)
                                else pluralStringResource(R.plurals.policy_summary_count, records.size, records.size),
                                fontWeight = FontWeight.SemiBold,
                                fontSize   = 15.sp,
                                color      = NewaxTheme.colors.textPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.policy_summary_subtitle),
                                fontSize = 12.sp,
                                color    = NewaxTheme.colors.textSecondary
                            )
                        }
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = null,
                            tint = NewaxTheme.colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (records.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PolicyDecision.entries.forEach { decision ->
                                val count = records.count { it.decision == decision }
                                DecisionStat(stringResource(decisionLabelRes(decision)), count, decisionColor(decision))
                            }
                        }

                        // Per-action-class pressure: which actions prompt the most
                        // human confirmations, so the user sees where to tighten policy.
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = NewaxTheme.colors.border)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.policy_most_approvals),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = NewaxTheme.colors.textTertiary
                        )
                        Spacer(Modifier.height(4.dp))
                        val breakdown = remember(records) { actionClassBreakdown(records) }
                        breakdown.take(5).forEach { stat ->
                            ActionClassRow(stat, onClick = { onOpenActionClass(stat.actionClass) })
                        }
                        if (breakdown.size > 5) {
                            Text(
                                stringResource(R.string.policy_more_classes, breakdown.size - 5),
                                fontSize = 11.sp,
                                color = NewaxTheme.colors.textTertiary
                            )
                        }

                        // CSV export — writes the currently filtered trail to the
                        // location the user picks (mirrors the desktop Policy tab).
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = NewaxTheme.colors.border)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    exportStatus = ExportStatus.Idle
                                    exportLauncher.launch("policy-audit-${csvTimestamp()}.csv")
                                },
                                enabled = records.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NewaxTheme.colors.textPrimary,
                                    contentColor = NewaxTheme.colors.surface,
                                    disabledContainerColor = NewaxTheme.colors.surfaceMuted,
                                    disabledContentColor = NewaxTheme.colors.textTertiary
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_export_csv), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.width(10.dp))
                            when (val status = exportStatus) {
                                is ExportStatus.Idle -> Text(
                                    stringResource(R.string.policy_export_hint),
                                    fontSize = 11.5.sp,
                                    color = NewaxTheme.colors.textTertiary,
                                    modifier = Modifier.weight(1f)
                                )
                                is ExportStatus.Done -> Text(
                                    stringResource(R.string.policy_exported),
                                    fontSize = 11.5.sp,
                                    color = NewaxTheme.colors.success,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                is ExportStatus.Failed -> Text(
                                    status.message,
                                    fontSize = 11.5.sp,
                                    color = NewaxTheme.colors.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (exportStatus is ExportStatus.Done && shareUri != null) {
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = shareExport,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textSecondary),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Rounded.Share, contentDescription = null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_share), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Filter ─────────────────────────────────────────────────────────
        if (records.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = filter == null,
                        onClick  = { filter = null },
                        label    = { Text(stringResource(R.string.policy_filter_all), fontSize = 12.sp) }
                    )
                    PolicyDecision.entries.forEach { decision ->
                        FilterChip(
                            selected = filter == decision,
                            onClick  = { filter = decision },
                            label    = { Text(stringResource(decisionLabelRes(decision)), fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        // ── States ─────────────────────────────────────────────────────────
        when {
            records.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.History, contentDescription = null, tint = NewaxTheme.colors.textTertiary, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.policy_empty_title), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.policy_empty_body), fontSize = 13.sp, color = NewaxTheme.colors.textTertiary)
                    }
                }
            }
            filtered.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.policy_empty_filtered, stringResource(decisionLabelRes(filter!!)).lowercase()), fontSize = 14.sp, color = NewaxTheme.colors.textTertiary)
                }
            }
            else -> items(filtered.asReversed(), key = {
                "${it.auditedAtMs}-${it.actionSummary}-${it.reason}"
            }) { record -> PolicyRecordCard(record, fmt) }
        }

        // ── Clear ──────────────────────────────────────────────────────────
        if (records.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textSecondary)
                ) { Text(stringResource(R.string.action_clear_history), fontSize = 14.sp) }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor   = NewaxTheme.colors.surface,
            title = { Text(stringResource(R.string.policy_clear_title), fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textPrimary) },
            text  = { Text(stringResource(R.string.policy_clear_body), color = NewaxTheme.colors.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        PolicyHolder.clearAuditHistory()
                        showClearDialog = false
                        version++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF1B1B1A))
                ) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.action_cancel), color = NewaxTheme.colors.textSecondary) }
            }
        )
    }
}

@Composable
private fun ActionClassRow(stat: ActionClassStat, onClick: () -> Unit) {
    val needsColor = if (stat.needsHuman > 0) NewaxTheme.colors.warning else NewaxTheme.colors.success
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stat.actionClass,
            fontSize   = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color      = NewaxTheme.colors.textPrimary,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.weight(1f)
        )
        Text(
            stringResource(R.string.policy_need_approval, stat.needsHuman, stat.total),
            fontSize   = 11.5.sp,
            color      = needsColor,
            fontWeight = if (stat.needsHuman > 0) FontWeight.SemiBold else FontWeight.Normal
        )
        if (stat.denied > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.policy_denied, stat.denied),
                fontSize = 11.5.sp,
                color    = NewaxTheme.colors.textTertiary
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.cd_open_policy, stat.actionClass),
            tint = NewaxTheme.colors.textTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun DecisionStat(label: String, count: Int, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            "$label $count",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun PolicyRecordCard(record: PolicyAuditRecord, fmt: SimpleDateFormat) {
    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(decisionColor(record.decision))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        record.actionSummary,
                        fontSize   = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color      = NewaxTheme.colors.textPrimary,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Text(
                        "${record.actionClass} · ${record.mode.name} · ${record.origin.name.lowercase()}",
                        fontSize   = 10.5.sp,
                        color      = NewaxTheme.colors.textTertiary,
                        fontFamily = FontFamily.Monospace,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(decisionLabelRes(record.decision)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = decisionColor(record.decision)
                )
            }
            if (record.reason.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    record.reason,
                    fontSize = 11.5.sp,
                    color    = NewaxTheme.colors.textSecondary,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                fmt.format(Date(record.auditedAtMs)),
                fontSize = 10.5.sp,
                color    = NewaxTheme.colors.textTertiary
            )
        }
    }
}
