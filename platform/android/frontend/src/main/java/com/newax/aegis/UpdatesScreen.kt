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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.agents.LearningEngine
import com.newax.aegis.db.entity.RiskLevel
import com.newax.aegis.db.entity.StagingRecord
import com.newax.aegis.db.entity.StagingStatus
import kotlinx.coroutines.delay

// ── Design tokens — same palette as the rest of the app ─────────────────────
private val U_Surface      = Color(0xFFFFFFFF)
private val U_SurfaceMuted = Color(0xFFF2F2EF)
private val U_Primary      = Color(0xFF1B1B1A)
private val U_TextPri      = Color(0xFF1B1B1A)
private val U_TextSec      = Color(0xFF686864)
private val U_TextTer      = Color(0xFF8D8D87)
private val U_Border       = Color(0xFFD8D8D3)
private val U_Green        = Color(0xFF22C55E)
private val U_GreenBg      = Color(0xFFDCFCE7)
private val U_Red          = Color(0xFFDC2626)
private val U_RedBg        = Color(0xFFFEE2E2)
private val U_Amber        = Color(0xFFF59E0B)
private val U_AmberBg      = Color(0xFFFEF3C7)
private val U_Blue         = Color(0xFF3B82F6)
private val U_BlueBg       = Color(0xFFDBEAFE)

private val RISK_ORDER = listOf(RiskLevel.CRITICAL, RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW)

private fun riskColor(risk: String): Pair<Color, Color> = when (risk) {
    RiskLevel.CRITICAL -> U_Red to U_RedBg
    RiskLevel.HIGH -> U_Amber to U_AmberBg
    RiskLevel.LOW -> U_Green to U_GreenBg
    else -> U_Blue to U_BlueBg
}

private fun protocolColor(protocol: String): Color = when (protocol) {
    "CRITIC" -> U_Amber
    "CROSS_AGENT" -> U_Blue
    else -> U_Green
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
        item { SectionLabel3("Pending system updates — grouped by urgency") }
        if (pending.isEmpty()) {
            item { EmptyUpdateCard("No pending updates — the system only changes with your approval.") }
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
        item { SectionLabel3("Recent decisions") }
        if (decisions.isEmpty()) {
            item { EmptyUpdateCard("No decisions yet — approvals and denials land here.") }
        } else {
            decisions.take(8).forEach { d ->
                item {
                    Text(
                        "${if (d.status == StagingStatus.DEPLOYED) "✓ approved" else "✗ denied"} · ${d.skillId} · ${d.title.take(60)} · ${relativeTime(d.decidedAtMs)}",
                        fontSize = 11.sp, color = U_TextSec, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel3("Reward signals (RLAIF-E feed)") }
        if (signals.isEmpty()) {
            item { EmptyUpdateCard("No learning signals yet — they appear as skills run and you give feedback.") }
        } else {
            signals.forEach { s ->
                item {
                    Text(
                        "[${if (s.reward > 0) "+" else ""}${s.reward}] ${s.summary.take(90)}",
                        fontSize = 11.sp,
                        color = if (s.reward < 0) U_Red else U_TextSec,
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
                "$methods ledger methods · $signals signals · $pending pending — all learning is device-local",
                fontSize = 11.sp, color = U_TextTer
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

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = U_Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, U_Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Evolution engine — RLAIF-E", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = U_TextPri)
                    Text("Exploitation uses the best-known method; exploration tests variations. Every change waits for your approval here.", fontSize = 12.sp, color = U_TextSec)
                }
                Switch(
                    checked = fuzzOn,
                    onCheckedChange = { on ->
                        LearningEngine.setFuzzEnabled(on)
                        fuzzOn = on
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = U_Primary,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = U_TextTer
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Exploration rate: ${(exploration * 100).toInt()}%", fontSize = 12.sp, color = U_TextSec, modifier = Modifier.width(140.dp))
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
                    message = if (staged > 0) "Fuzz pass: $staged candidate(s) staged for approval." else "Fuzz pass: nothing new to propose (fuzzer off, or candidates already pending)."
                    lastFuzz = System.currentTimeMillis()
                }) { Text("Run fuzz pass now", fontSize = 13.sp) }
                Spacer(Modifier.width(10.dp))
                Text("last fuzz: ${relativeTime(lastFuzz)}", fontSize = 11.sp, color = U_TextTer)
            }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = U_Green) }
        }
    }
}

@Composable
private fun PendingUpdateCard(record: StagingRecord) {
    var showDiff by remember { mutableStateOf(false) }
    val (riskFg, riskBg) = riskColor(record.riskLevel)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (record.changeType == "MEMORY_RULE") U_BlueBg.copy(alpha = 0.35f) else U_Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, U_Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(riskBg)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) { Text(record.riskLevel, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = riskFg) }
                Spacer(Modifier.width(6.dp))
                Text(record.changeType, fontSize = 10.sp, color = U_TextTer, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(6.dp))
                Text(record.protocol, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = protocolColor(record.protocol))
                Spacer(Modifier.weight(1f))
                Text(relativeTime(record.createdAtMs), fontSize = 10.sp, color = U_TextTer)
            }
            Spacer(Modifier.height(8.dp))
            Text(record.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = U_TextPri)
            Spacer(Modifier.height(4.dp))
            Text(record.summary, fontSize = 13.sp, color = U_TextSec, lineHeight = 19.sp)
            if (record.agentId.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("authored by ${record.agentId}", fontSize = 11.sp, color = U_TextTer)
            }
            if (record.diffBefore.isNotBlank() || record.diffAfter.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showDiff = !showDiff }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showDiff) "Hide diff" else "Show diff", fontSize = 12.sp, color = U_Blue)
                }
                if (showDiff) {
                    Column(Modifier.fillMaxWidth()) {
                        DiffLine("before", record.diffBefore, U_Red, U_RedBg)
                        Spacer(Modifier.height(4.dp))
                        DiffLine("after", record.diffAfter, U_Green, U_GreenBg)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { LearningEngine.approve(record.stagingId) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = U_Primary)
                ) { Text("Approve", fontSize = 13.sp) }
                TextButton(onClick = { LearningEngine.deny(record.stagingId) }) {
                    Text("Deny", fontSize = 13.sp, color = U_Red)
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
            fontSize = 11.sp, color = U_TextPri, fontFamily = FontFamily.Monospace,
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
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(U_SurfaceMuted)
            .padding(14.dp)
    ) { Text(text, fontSize = 12.sp, color = U_TextTer) }
}

@Composable
private fun SectionLabel3(text: String) {
    Text(
        text,
        fontSize = 11.sp, fontWeight = FontWeight.Medium, color = U_TextTer,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
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
        colors = CardDefaults.cardColors(containerColor = U_Primary),
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
                    "$count learning update${if (count == 1) "" else "s"} ready for review",
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White
                )
                Text("A skill method or knowledge rule was staged — approve or deny it.", fontSize = 11.sp, color = Color(0xFFD4D4CF))
            }
            Spacer(Modifier.width(10.dp))
            Text("Review", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.width(6.dp))
            Text("✕", fontSize = 14.sp, color = Color(0xFFD4D4CF), modifier = Modifier.clickable(onClick = onDismiss))
        }
    }
}
