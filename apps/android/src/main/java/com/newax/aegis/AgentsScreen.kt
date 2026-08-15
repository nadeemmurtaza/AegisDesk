package com.newax.aegis

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.agents.AgentRegistry
import com.newax.aegis.agents.AgentResult
import com.newax.aegis.agents.AgentRouter
import com.newax.aegis.agents.AgentRuntimeEngine
import com.newax.aegis.agents.AgentStream
import com.newax.aegis.db.entity.AgentEntity
import com.newax.aegis.db.entity.AgentHealthStatus
import com.newax.aegis.db.entity.SessionPhase
import com.newax.aegis.db.entity.SessionStatus
import com.newax.aegis.ui.theme.NewaxLightColors

// ── Design tokens — same palette as the rest of the app ─────────────────────
private val Surface = NewaxLightColors.surface
private val SurfaceMuted = NewaxLightColors.surfaceMuted
private val Primary = NewaxLightColors.textPrimary
private val TextPri = NewaxLightColors.textPrimary
private val TextSec = NewaxLightColors.textSecondary
private val TextTer = NewaxLightColors.textTertiary
private val Border = NewaxLightColors.border
private val AccentGreen = NewaxLightColors.success
private val AccentRed = NewaxLightColors.error

/**
 * The multi-agent management surface (docs/AGENTS_DESIGN.md; R13): installed
 * agents with enable/disable + uninstall (imported only), zip import/upgrade
 * via the system file picker, and a live routing preview showing which agent
 * dominates a request and which support it — including per-step dominance for
 * multi-step tasks.
 */
@Composable
fun AgentsScreen(padding: PaddingValues, onContinueTask: (String) -> Unit = {}) {
    val context = LocalContext.current
    var agents by remember { mutableStateOf(AgentRegistry.agents()) }
    var message by remember { mutableStateOf<String?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            message = AgentRegistry.importAgent(uri)
            agents = AgentRegistry.agents()
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
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("${agents.size} agents", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Disabled agents never route. Import a zip (agent.json + skills) to install or upgrade.",
                        fontSize = 13.sp, color = TextSec
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }) {
                            Text("Import agent (.zip)")
                        }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = { agents = AgentRegistry.agents() }) { Text("Refresh") }
                    }
                    message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 13.sp, color = if (it.startsWith("Import failed")) AccentRed else AccentGreen)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel2("Installed agents") }

        items(agents, key = { it.agentId }) { agent ->
            AgentCard(
                agent = agent,
                onToggle = { on ->
                    AgentRegistry.setEnabled(agent.agentId, on)
                    agents = AgentRegistry.agents()
                },
                onUninstall = {
                    AgentRegistry.uninstall(agent.agentId)
                    agents = AgentRegistry.agents()
                }
            )
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel2("Routing preview — who dominates what") }
        item { RoutingPreview() }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel2("Agent runtime — PRAM controller (run / abort / get_status / health_check)") }
        item { AgentRuntimePanel(onContinueTask) }
    }
}

private val AccentBlue = NewaxLightColors.info
private val AccentAmber = NewaxLightColors.warning

private fun elapsed(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

private fun phaseColor(phase: String): Color = when (phase) {
    SessionPhase.RUNNING_TOOL -> AccentAmber
    SessionPhase.RESTORED -> AccentBlue
    SessionPhase.DONE -> AccentGreen
    else -> TextTer
}

private fun healthColor(status: String?): Color = when (status) {
    AgentHealthStatus.FAULTED -> AccentRed
    AgentHealthStatus.DEGRADED -> AccentAmber
    else -> AccentGreen
}

/**
 * The runtime surface (docs/AGENTS_DESIGN.md §runtime; R13): live sessions
 * with phase + elapsed + Abort (Cancel) / Freeze (skill.sys.serialize_state),
 * the health-audit ledger with Health check / Restore (skill.sys.health_audit),
 * Thaw for frozen state, Continue for restored tasks, and the live MCP stream
 * feed (skill.sys.mcp_stream). Live-updates via the stream bus.
 */
@Composable
private fun AgentRuntimePanel(onContinueTask: (String) -> Unit) {
    // Observe the stream properly. The previous version collected into a `tick`
    // counter to force recomposition — but nothing ever *read* `tick`, so Compose
    // tracked no dependency on it and the panel never refreshed. A hand-rolled
    // invalidation that does not invalidate is worse than none: it looks handled.
    val events by AgentStream.events.collectAsState()
    val feed = events.takeLast(5)
    val sessions = AgentRuntimeEngine.activeSessions()
    val frozen = AgentRuntimeEngine.frozenSessions()
    val healthRows = AgentRuntimeEngine.allHealth().associateBy { it.agentId }
    val agentNames = remember { AgentRegistry.agents().associate { it.agentId to it.name } }
    val name = { id: String -> agentNames[id] ?: id }

    // ── live sessions ───────────────────────────────────────────────────────
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Live sessions", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri)
            Spacer(Modifier.height(4.dp))
            Text("Every agent runs through the same controller — run(), abort(), get_status(), health_check().", fontSize = 12.sp, color = TextSec)
            if (sessions.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("No sessions running.", fontSize = 12.sp, color = TextTer)
            }
            sessions.forEach { s ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(phaseColor(s.phase))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${name(s.agentId)} — ${s.phase}", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextPri)
                        Text("${s.taskPrompt.take(70)} · ${elapsed(s.startedAtMs)}", fontSize = 11.sp, color = TextTer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { AgentRuntimeEngine.controllerFor(s.agentId).abort() }) {
                        Text("Abort", fontSize = 12.sp, color = AccentRed)
                    }
                    TextButton(onClick = { AgentRuntimeEngine.freeze(s.sessionId) }) {
                        Text("Freeze", fontSize = 12.sp)
                    }
                }
                if (s.phase == SessionPhase.RESTORED) {
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = { onContinueTask(s.taskPrompt) },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Continue in chat", fontSize = 12.sp, color = AccentBlue) }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── health audit ────────────────────────────────────────────────────────
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Health audit — skill.sys.health_audit", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri)
            Spacer(Modifier.height(4.dp))
            Text("A FAULTED agent is quarantined (auto-disabled) until you restore it. Soft issues are DEGRADED and monitored.", fontSize = 12.sp, color = TextSec)
            AgentRegistry.agents().forEach { agent ->
                val h = healthRows[agent.agentId]
                val status = h?.status
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(healthColor(status))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(agent.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextPri)
                        val detail = h?.detail
                        Text(
                            if (detail.isNullOrBlank()) "${status ?: "not audited yet"} · faults: ${h?.faultCount ?: 0}" else "$status · ${detail.take(60)}",
                            fontSize = 11.sp, color = TextTer, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { AgentRuntimeEngine.controllerFor(agent.agentId).healthCheck() }) {
                        Text("Check", fontSize = 12.sp)
                    }
                    if (status == AgentHealthStatus.FAULTED) {
                        TextButton(onClick = { AgentRuntimeEngine.recover(agent.agentId) }) {
                            Text("Restore", fontSize = 12.sp, color = AccentGreen)
                        }
                    }
                    if (frozen.any { it.agentId == agent.agentId }) {
                        TextButton(onClick = { AgentRuntimeEngine.thaw(agent.agentId) }) {
                            Text("Thaw", fontSize = 12.sp, color = AccentBlue)
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── stream feed ─────────────────────────────────────────────────────────
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Stream feed — skill.sys.mcp_stream", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri)
            Spacer(Modifier.height(4.dp))
            Text("Agents stream their progress here in real time — structured events, never raw chatter.", fontSize = 12.sp, color = TextSec)
            if (feed.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("No stream events yet.", fontSize = 12.sp, color = TextTer)
            }
            feed.forEach { e ->
                Spacer(Modifier.height(4.dp))
                Text("[${e.type}] ${name(e.agentId)} ${e.phase}: ${e.text.take(80)}", fontSize = 11.sp, color = TextSec, fontFamily = FontFamily.Monospace)
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── recent runs (strict result/error blocks, read by the core app) ──────
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Recent runs — structured blocks", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri)
            Spacer(Modifier.height(4.dp))
            Text("The app reads these strict blocks ({\"status\":…}) to render results and errors cleanly.", fontSize = 12.sp, color = TextSec)
            val recent = AgentRuntimeEngine.recentSessions(4)
            if (recent.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("No completed runs yet.", fontSize = 12.sp, color = TextTer)
            }
            recent.forEach { r ->
                Spacer(Modifier.height(8.dp))
                val parsed = AgentResult.parse(r.resultJson.ifBlank { r.errorJson })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                when (parsed?.status) {
                                    "success" -> AccentGreen
                                    "error" -> AccentRed
                                    else -> TextTer
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${name(r.agentId)} — ${r.status}", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextPri)
                        val line = when {
                            parsed == null -> r.taskPrompt.take(60)
                            parsed.status == "success" -> parsed.summary
                            else -> "[${parsed.errorType}] ${parsed.message}"
                        }
                        Text(line.take(90), fontSize = 11.sp, color = if (parsed?.status == "error") AccentRed else TextTer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentCard(agent: AgentEntity, onToggle: (Boolean) -> Unit, onUninstall: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (agent.enabled) Surface else SurfaceMuted),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (agent.enabled) AccentGreen else TextTer)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${agent.name} — v${agent.version}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri)
                    Text("${agent.category} · ${agent.source}", fontSize = 11.sp, color = TextTer)
                }
                Switch(
                    checked = agent.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = Primary,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = TextTer
                    )
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(agent.description, fontSize = 13.sp, color = TextSec)
            if (agent.keywords.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(agent.keywords.split(',').joinToString(" · ") { it.trim() }, fontSize = 11.sp, color = TextTer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (agent.source != "builtin") {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onUninstall) { Text("Uninstall", fontSize = 12.sp, color = AccentRed) }
            }
        }
    }
}

/** Type a request → see each step's dominant agent + supporters. */
@Composable
private fun RoutingPreview() {
    var input by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf(emptyList<AgentRouter.StepPlan>()) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Multi-step tasks route per step — step 1 planning → Planning Agent, step 2 coding → Coding Agent.", fontSize = 13.sp, color = TextSec)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Try: plan a feature then write the code") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { plan = AgentRouter.planFor(input) },
                enabled = input.isNotBlank()
            ) { Text("Route") }
            if (plan.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                plan.forEachIndexed { index, step ->
                    val dominant = step.route?.dominant
                    Text(
                        "Step ${index + 1}: \"${step.text.take(60)}\" → " +
                            (dominant?.let { "${it.name} (dominant)" } ?: "assistant (no agent matched)") +
                            (step.route?.supporters?.takeIf { it.isNotEmpty() }?.let { " + ${it.joinToString(", ") { s -> s.name }} (support)" } ?: ""),
                        fontSize = 12.sp, color = TextPri, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel2(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextTer,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}
