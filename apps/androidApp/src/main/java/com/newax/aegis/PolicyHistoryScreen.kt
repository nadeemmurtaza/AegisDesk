package com.newax.aegis

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
import androidx.compose.material.icons.rounded.History
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.engine.audit.ActionClassStat
import com.newax.aegis.engine.audit.actionClassBreakdown
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Design tokens (REFINED_THEME.md, match CapabilitiesScreen) ──────────────
private val Surface      = Color(0xFFFFFFFF)
private val SurfaceMuted = Color(0xFFF2F2EF)
private val TextPri      = Color(0xFF1B1B1A)
private val TextSec      = Color(0xFF686864)
private val TextTer      = Color(0xFF8D8D87)
private val Border       = Color(0xFFD8D8D3)
private val AutoCol      = Color(0xFF22C55E)
private val ApprovalCol  = Color(0xFFF59E0B)
private val StrongCol    = Color(0xFFEF4444)
private val DenyCol      = Color(0xFF64748B)

private fun decisionColor(decision: PolicyDecision): Color = when (decision) {
    PolicyDecision.AUTO_EXECUTE     -> AutoCol
    PolicyDecision.REQUIRE_APPROVAL -> ApprovalCol
    PolicyDecision.REQUIRE_STRONG   -> StrongCol
    PolicyDecision.DENY             -> DenyCol
}

private fun decisionLabel(decision: PolicyDecision): String = when (decision) {
    PolicyDecision.AUTO_EXECUTE     -> "Auto"
    PolicyDecision.REQUIRE_APPROVAL -> "Approval"
    PolicyDecision.REQUIRE_STRONG   -> "Strong"
    PolicyDecision.DENY             -> "Denied"
}

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
    val fmt = remember { SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()) }

    val filtered = if (filter == null) records else records.filter { it.decision == filter }

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
                colors    = CardDefaults.cardColors(containerColor = Surface),
                border    = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (records.isEmpty()) "No policy decisions"
                                else "${records.size} ${if (records.size == 1) "decision" else "decisions"}",
                                fontWeight = FontWeight.SemiBold,
                                fontSize   = 15.sp,
                                color      = TextPri
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Recorded across sessions — who asked, what was decided",
                                fontSize = 12.sp,
                                color    = TextSec
                            )
                        }
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = null,
                            tint = TextSec,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (records.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PolicyDecision.entries.forEach { decision ->
                                val count = records.count { it.decision == decision }
                                DecisionStat(decisionLabel(decision), count, decisionColor(decision))
                            }
                        }

                        // Per-action-class pressure: which actions prompt the most
                        // human confirmations, so the user sees where to tighten policy.
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Border)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Actions needing most approvals",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextTer
                        )
                        Spacer(Modifier.height(4.dp))
                        val breakdown = remember(records) { actionClassBreakdown(records) }
                        breakdown.take(5).forEach { stat ->
                            ActionClassRow(stat, onClick = { onOpenActionClass(stat.actionClass) })
                        }
                        if (breakdown.size > 5) {
                            Text(
                                "+ ${breakdown.size - 5} more action classes",
                                fontSize = 11.sp,
                                color = TextTer
                            )
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
                        label    = { Text("All", fontSize = 12.sp) }
                    )
                    PolicyDecision.entries.forEach { decision ->
                        FilterChip(
                            selected = filter == decision,
                            onClick  = { filter = decision },
                            label    = { Text(decisionLabel(decision), fontSize = 12.sp) }
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
                        Icon(Icons.Rounded.History, contentDescription = null, tint = TextTer, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("No policy decisions yet", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextSec)
                        Spacer(Modifier.height(4.dp))
                        Text("Decisions appear here when Aegis evaluates an action", fontSize = 13.sp, color = TextTer)
                    }
                }
            }
            filtered.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text("No ${decisionLabel(filter!!).lowercase()} decisions recorded", fontSize = 14.sp, color = TextTer)
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
                    border   = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = TextSec)
                ) { Text("Clear history", fontSize = 14.sp) }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor   = Surface,
            title = { Text("Clear policy history?", fontWeight = FontWeight.SemiBold, color = TextPri) },
            text  = { Text("This permanently removes every recorded policy decision. Future evaluations are still audited.", color = TextSec) },
            confirmButton = {
                Button(
                    onClick = {
                        PolicyHolder.clearAuditHistory()
                        showClearDialog = false
                        version++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF1B1B1A))
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel", color = TextSec) }
            }
        )
    }
}

@Composable
private fun ActionClassRow(stat: ActionClassStat, onClick: () -> Unit) {
    val needsColor = if (stat.needsHuman > 0) ApprovalCol else AutoCol
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
            color      = TextPri,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.weight(1f)
        )
        Text(
            "${stat.needsHuman} of ${stat.total} need approval",
            fontSize   = 11.5.sp,
            color      = needsColor,
            fontWeight = if (stat.needsHuman > 0) FontWeight.SemiBold else FontWeight.Normal
        )
        if (stat.denied > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "· ${stat.denied} denied",
                fontSize = 11.5.sp,
                color    = DenyCol
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = "Open policy for ${stat.actionClass}",
            tint = TextTer,
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
        colors    = CardDefaults.cardColors(containerColor = Surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, Border),
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
                        color      = TextPri,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Text(
                        "${record.actionClass} · ${record.mode.name} · ${record.origin.name.lowercase()}",
                        fontSize   = 10.5.sp,
                        color      = TextTer,
                        fontFamily = FontFamily.Monospace,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    decisionLabel(record.decision),
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
                    color    = TextSec,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                fmt.format(Date(record.auditedAtMs)),
                fontSize = 10.5.sp,
                color    = TextTer
            )
        }
    }
}
