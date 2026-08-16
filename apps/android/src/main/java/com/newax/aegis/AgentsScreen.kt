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
import androidx.compose.ui.res.stringResource
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
import com.newax.aegis.db.entity.AgentHealthStatus
import com.newax.aegis.db.entity.SessionPhase
import com.newax.aegis.db.entity.SessionStatus
import com.newax.aegis.ui.components.AgentCard
import com.newax.aegis.ui.theme.NewaxTheme

// ── Design tokens — same palette as the rest of the app ─────────────────────
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
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.agents_count_header, agents.size), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.agents_disabled_note), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }) {
                            Text(stringResource(R.string.action_import_agent_zip))
                        }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = { agents = AgentRegistry.agents() }) { Text(stringResource(R.string.action_refresh)) }
                    }
                    message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 13.sp, color = if (it.startsWith("Import failed")) NewaxTheme.colors.error else NewaxTheme.colors.success)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel2(stringResource(R.string.agents_section_installed)) }

        items(agents, key = { it.agentId }) { agent ->
            // T3.4c: the shared agent card (docs/UI_DESIGN.md §8 — AgentCard).
            AgentCard(
                title       = stringResource(R.string.agents_name_version, agent.name, agent.version),
                metaLabel   = "${agent.category} · ${agent.source}",
                description = agent.description,
                enabled     = agent.enabled,
                onToggle    = { on ->
                    AgentRegistry.setEnabled(agent.agentId, on)
                    agents = AgentRegistry.agents()
                },
                tagsLabel = agent.keywords.split(',').joinToString(" · ") { it.trim() }.ifBlank { null },
                uninstallLabel = if (agent.source != "builtin") stringResource(R.string.action_uninstall) else null,
                onUninstall = if (agent.source != "builtin") {
                    {
                        AgentRegistry.uninstall(agent.agentId)
                        agents = AgentRegistry.agents()
                    }
                } else null
            )
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel2(stringResource(R.string.agents_section_routing)) }
        item { RoutingPreview() }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel2(stringResource(R.string.agents_section_runtime)) }
        item { AgentRuntimePanel(onContinueTask) }
    }
}

private fun elapsed(context: android.content.Context, ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return if (s < 60) context.getString(R.string.agents_elapsed_seconds, s)
    else context.getString(R.string.agents_elapsed_min_sec, s / 60, s % 60)
}

@Composable
private fun phaseColor(phase: String): Color = when (phase) {
    SessionPhase.RUNNING_TOOL -> NewaxTheme.colors.warning
    SessionPhase.RESTORED -> NewaxTheme.colors.info
    SessionPhase.DONE -> NewaxTheme.colors.success
    else -> NewaxTheme.colors.textTertiary
}

@Composable
private fun healthColor(status: String?): Color = when (status) {
    AgentHealthStatus.FAULTED -> NewaxTheme.colors.error
    AgentHealthStatus.DEGRADED -> NewaxTheme.colors.warning
    else -> NewaxTheme.colors.success
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
    val context = LocalContext.current
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
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.agents_live_sessions), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.agents_controller_note), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
            if (sessions.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.agents_no_sessions), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
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
                        Text("${name(s.agentId)} — ${s.phase}", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary)
                        Text("${s.taskPrompt.take(70)} · ${elapsed(context, s.startedAtMs)}", fontSize = 11.sp, color = NewaxTheme.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { AgentRuntimeEngine.controllerFor(s.agentId).abort() }) {
                        Text(stringResource(R.string.action_abort), fontSize = 12.sp, color = NewaxTheme.colors.error)
                    }
                    TextButton(onClick = { AgentRuntimeEngine.freeze(s.sessionId) }) {
                        Text(stringResource(R.string.action_freeze), fontSize = 12.sp)
                    }
                }
                if (s.phase == SessionPhase.RESTORED) {
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = { onContinueTask(s.taskPrompt) },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text(stringResource(R.string.action_continue_in_chat), fontSize = 12.sp, color = NewaxTheme.colors.info) }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── health audit ────────────────────────────────────────────────────────
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.agents_health_audit), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.agents_health_desc), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
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
                        Text(agent.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary)
                        val detail = h?.detail
                        Text(
                            if (detail.isNullOrBlank()) context.getString(R.string.agents_health_detail_empty, status ?: context.getString(R.string.agents_not_audited), h?.faultCount ?: 0)
                            else context.getString(R.string.agents_health_detail, status, detail.take(60)),
                            fontSize = 11.sp, color = NewaxTheme.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { AgentRuntimeEngine.controllerFor(agent.agentId).healthCheck() }) {
                        Text(stringResource(R.string.action_check), fontSize = 12.sp)
                    }
                    if (status == AgentHealthStatus.FAULTED) {
                        TextButton(onClick = { AgentRuntimeEngine.recover(agent.agentId) }) {
                            Text(stringResource(R.string.action_restore), fontSize = 12.sp, color = NewaxTheme.colors.success)
                        }
                    }
                    if (frozen.any { it.agentId == agent.agentId }) {
                        TextButton(onClick = { AgentRuntimeEngine.thaw(agent.agentId) }) {
                            Text(stringResource(R.string.action_thaw), fontSize = 12.sp, color = NewaxTheme.colors.info)
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
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.agents_stream_feed), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.agents_stream_desc), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
            if (feed.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.agents_no_stream), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
            }
            feed.forEach { e ->
                Spacer(Modifier.height(4.dp))
                Text("[${e.type}] ${name(e.agentId)} ${e.phase}: ${e.text.take(80)}", fontSize = 11.sp, color = NewaxTheme.colors.textSecondary, fontFamily = FontFamily.Monospace)
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── recent runs (strict result/error blocks, read by the core app) ──────
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.agents_recent_runs), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.agents_blocks_desc), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
            val recent = AgentRuntimeEngine.recentSessions(4)
            if (recent.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.agents_no_runs), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
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
                                    "success" -> NewaxTheme.colors.success
                                    "error" -> NewaxTheme.colors.error
                                    else -> NewaxTheme.colors.textTertiary
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${name(r.agentId)} — ${r.status}", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary)
                        val line = when {
                            parsed == null -> r.taskPrompt.take(60)
                            parsed.status == "success" -> parsed.summary
                            else -> "[${parsed.errorType}] ${parsed.message}"
                        }
                        Text(line.take(90), fontSize = 11.sp, color = if (parsed?.status == "error") NewaxTheme.colors.error else NewaxTheme.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
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
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.agents_route_desc), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text(stringResource(R.string.agents_route_placeholder)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { plan = AgentRouter.planFor(input) },
                enabled = input.isNotBlank()
            ) { Text(stringResource(R.string.action_route)) }
            if (plan.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                plan.forEachIndexed { index, step ->
                    val dominant = step.route?.dominant
                    val dominantLabel = dominant?.let { stringResource(R.string.agents_step_dominant, it.name) }
                        ?: stringResource(R.string.agents_step_no_match)
                    val supportLabel = step.route?.supporters?.takeIf { it.isNotEmpty() }?.let {
                        stringResource(R.string.agents_step_support, it.joinToString(", ") { s -> s.name })
                    } ?: ""
                    Text(
                        stringResource(R.string.agents_step_line, index + 1, step.text.take(60), dominantLabel + supportLabel),
                        fontSize = 12.sp, color = NewaxTheme.colors.textPrimary, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel2(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textTertiary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}
