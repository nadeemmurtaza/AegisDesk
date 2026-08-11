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
import androidx.compose.runtime.getValue
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
import com.newax.aegis.agents.AgentRouter
import com.newax.aegis.db.entity.AgentEntity

// ── Design tokens — same palette as the rest of the app ─────────────────────
private val Surface = Color(0xFFFFFFFF)
private val SurfaceMuted = Color(0xFFF2F2EF)
private val Primary = Color(0xFF1B1B1A)
private val TextPri = Color(0xFF1B1B1A)
private val TextSec = Color(0xFF686864)
private val TextTer = Color(0xFF8D8D87)
private val Border = Color(0xFFD8D8D3)
private val AccentGreen = Color(0xFF22C55E)
private val AccentRed = Color(0xFFDC2626)

/**
 * The multi-agent management surface (docs/AGENTS_DESIGN.md; R13): installed
 * agents with enable/disable + uninstall (imported only), zip import/upgrade
 * via the system file picker, and a live routing preview showing which agent
 * dominates a request and which support it — including per-step dominance for
 * multi-step tasks.
 */
@Composable
fun AgentsScreen(padding: PaddingValues) {
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
