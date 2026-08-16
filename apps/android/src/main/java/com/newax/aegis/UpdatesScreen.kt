package com.newax.aegis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.agents.LearningEngine
import com.newax.aegis.db.entity.RiskLevel
import com.newax.aegis.db.entity.StagingRecord
import com.newax.aegis.ui.components.EmptyState
import com.newax.aegis.ui.components.SectionHeader
import com.newax.aegis.ui.components.StatusChip
import com.newax.aegis.db.entity.StagingStatus
import kotlinx.coroutines.delay
import com.newax.aegis.ui.theme.NewaxTheme

// ── Design tokens — same palette as the rest of the app ─────────────────────
private val RISK_ORDER = listOf(RiskLevel.CRITICAL, RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW)

private fun riskColor(risk: String): Pair<Color, Color> = when (risk) {
    RiskLevel.CRITICAL -> NewaxTheme.colors.error to NewaxTheme.colors.errorFill
    RiskLevel.HIGH -> NewaxTheme.colors.warning to NewaxTheme.colors.warningFill
    RiskLevel.LOW -> NewaxTheme.colors.success to NewaxTheme.colors.successFill
    else -> NewaxTheme.colors.info to NewaxTheme.colors.infoFill
}

private fun protocolColor(protocol: String): Color = when (protocol) {
    "CRITIC" -> NewaxTheme.colors.warning
    "CROSS_AGENT" -> NewaxTheme.colors.info
    else -> NewaxTheme.colors.success
}

private fun relativeTime(ms: Long): String {
    if (ms <= 0L) return "never"
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 0 -> "just now"
        diff < 60_000 -> "${diff / 1000}s ago"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> "${diff / 86_400_000}d ago"
    }
}

/**
 * The Pending System Updates surface (docs/AGENTS_DESIGN.md §evolution; R13):
 * every staged mutation from the RLAIF-E engine — deterministic fix
 * proposals, fuzzer candidates, critic knowledge corrections, cross-agent
 * contracts — grouped by urgency/risk (CRITICAL at the top, LOW at the
 * bottom), each with a plain-English explanation card and an expandable
 * color-coded diff, plus the Approve/Deny gate. Also hosts the evolution
 * engine controls (exploration rate, fuzzer switch, run-now) and the recent
 * decisions + reward-signal feeds.
 */
@Composable
fun UpdatesScreen(padding: PaddingValues) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            tick++
        }
    }
    val pending = remember(tick) { LearningEngine.pendingUpdates() }
    val decisions = remember(tick) { LearningEngine.recentDecisions(20) }
    val signals = remember(tick) { LearningEngine.signals(12) }
    val stats = remember(tick) { LearningEngine.stats() }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item { EvolutionControlsCard() }
        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel3(stringResource(R.string.updates_section_pending)) }
        if (pending.isEmpty()) {
            item { EmptyUpdateCard(stringResource(R.string.updates_empty_pending)) }
        } else {
            RISK_ORDER.forEach { risk ->
                val group = pending.filter { it.riskLevel == risk }
                if (group.isNotEmpty()) {
                    item {
                        Text(
                            risk.lowercase().replaceFirstChar { it.uppercase() },
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = riskColor(risk).first,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }
                    items(group.size) { i -> PendingUpdateCard(group[i]) }
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel3(stringResource(R.string.updates_section_decisions)) }
        if (decisions.isEmpty()) {
            item { EmptyUpdateCard(stringResource(R.string.updates_empty_decisions)) }
        } else {
            decisions.take(8).forEach { d ->
                item {
                    Text(
                        "${if (d.status == StagingStatus.DEPLOYED) "✓ approved" else "✗ denied"} · ${d.skillId} · ${d.title.take(60)} · ${relativeTime(d.decidedAtMs)}",
                        fontSize = 11.sp, color = NewaxTheme.colors.textSecondary, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel3(stringResource(R.string.updates_section_signals)) }
        if (signals.isEmpty()) {
            item { EmptyUpdateCard(stringResource(R.string.updates_empty_signals)) }
        } else {
            signals.forEach { s ->
                item {
                    Text(
                        "[${if (s.reward > 0) "+" else ""}${s.reward}] ${s.summary.take(90)}",
                        fontSize = 11.sp,
                        color = if (s.reward < 0) NewaxTheme.colors.error else NewaxTheme.colors.textSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item {
            val methods = stats["methods"] ?: 0
            val signals = stats["signals"] ?: 0
            val pending = stats["pending"] ?: 0
            Text(
                stringResource(R.string.updates_stats_footer, methods, signals, pending),
                fontSize = 11.sp, color = NewaxTheme.colors.textTertiary
            )
        }
    }
}

@Composable
private fun EvolutionControlsCard() {
    var exploration by remember { mutableStateOf(LearningEngine.explorationRate().toFloat()) }
    var fuzzOn by remember { mutableStateOf(LearningEngine.fuzzEnabled()) }
    var lastFuzz by remember { mutableStateOf(LearningEngine.lastFuzzAtMs()) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.updates_engine_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                    Text(stringResource(R.string.updates_engine_desc), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
                }
                Switch(
                    checked = fuzzOn,
                    onCheckedChange = { on ->
                        LearningEngine.setFuzzEnabled(on)
                        fuzzOn = on
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = NewaxTheme.colors.textPrimary,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = NewaxTheme.colors.textTertiary
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.updates_exploration_rate, (exploration * 100).toInt()), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, modifier = Modifier.width(140.dp))
                Slider(
                    value = exploration,
                    onValueChange = { exploration = it },
                    onValueChangeFinished = { LearningEngine.setExplorationRate(exploration.toDouble()) },
                    valueRange = 0f..0.5f
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    val staged = LearningEngine.fuzzPass()
                    message = if (staged > 0) context.getString(R.string.updates_fuzz_staged, staged)
                    else context.getString(R.string.updates_fuzz_nothing)
                    lastFuzz = System.currentTimeMillis()
                }) { Text(stringResource(R.string.action_run_fuzz), fontSize = 13.sp) }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.updates_last_fuzz, relativeTime(lastFuzz)), fontSize = 11.sp, color = NewaxTheme.colors.textTertiary)
            }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = NewaxTheme.colors.success) }
        }
    }
}

@Composable
private fun PendingUpdateCard(record: StagingRecord) {
    var showDiff by remember { mutableStateOf(false) }
    val (riskFg, riskBg) = riskColor(record.riskLevel)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (record.changeType == "MEMORY_RULE") NewaxTheme.colors.infoFill.copy(alpha = 0.35f) else NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    label = record.riskLevel,
                    color = riskFg,
                    fill  = riskBg
                )
                Spacer(Modifier.width(6.dp))
                Text(record.changeType, fontSize = 10.sp, color = NewaxTheme.colors.textTertiary, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(6.dp))
                Text(record.protocol, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = protocolColor(record.protocol))
                Spacer(Modifier.weight(1f))
                Text(relativeTime(record.createdAtMs), fontSize = 10.sp, color = NewaxTheme.colors.textTertiary)
            }
            Spacer(Modifier.height(8.dp))
            Text(record.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(record.summary, fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp)
            if (record.agentId.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.updates_authored_by, record.agentId), fontSize = 11.sp, color = NewaxTheme.colors.textTertiary)
            }
            if (record.diffBefore.isNotBlank() || record.diffAfter.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showDiff = !showDiff }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showDiff) stringResource(R.string.updates_hide_diff) else stringResource(R.string.updates_show_diff), fontSize = 12.sp, color = NewaxTheme.colors.info)
                }
                if (showDiff) {
                    Column(Modifier.fillMaxWidth()) {
                        DiffLine(stringResource(R.string.updates_diff_before), record.diffBefore, NewaxTheme.colors.error, NewaxTheme.colors.errorFill)
                        Spacer(Modifier.height(4.dp))
                        DiffLine(stringResource(R.string.updates_diff_after), record.diffAfter, NewaxTheme.colors.success, NewaxTheme.colors.successFill)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { LearningEngine.approve(record.stagingId) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary)
                ) { Text(stringResource(R.string.action_approve), fontSize = 13.sp) }
                TextButton(onClick = { LearningEngine.deny(record.stagingId) }) {
                    Text(stringResource(R.string.action_deny), fontSize = 13.sp, color = NewaxTheme.colors.error)
                }
            }
        }
    }
}

@Composable
private fun DiffLine(label: String, text: String, color: Color, bg: Color) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
        Text(
            text.take(400),
            fontSize = 11.sp, color = NewaxTheme.colors.textPrimary, fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .padding(8.dp)
        )
    }
}

@Composable
private fun EmptyUpdateCard(text: String) {
    // T3.4: the shared empty surface — one look for every screen's "nothing
    // here yet".
    EmptyState(
        title = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    )
}

@Composable
private fun SectionLabel3(text: String) {
    // T3.4: the shared section header — `heading()` semantics so screen readers
    // can navigate by heading instead of reading linearly.
    SectionHeader(title = text)
}

// ── Live notification banner (shown the minute a patch is ready) ────────────

/**
 * The live pop-up: rendered above the content when a new staging record
 * exists. Tap Review to open the Updates tab; dismiss until the queue
 * empties (a new patch brings it back).
 */
@Composable
fun PendingUpdatesBanner(
    count: Int,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.textPrimary),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Row(
            Modifier
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.updates_banner_count, count, count),
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White
                )
                Text(stringResource(R.string.updates_banner_desc), fontSize = 11.sp, color = Color(0xFFD4D4CF))
            }
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.action_review), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.width(6.dp))
            Text("✕", fontSize = 14.sp, color = Color(0xFFD4D4CF), modifier = Modifier.clickable(onClick = onDismiss))
        }
    }
}
