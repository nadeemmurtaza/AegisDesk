package com.newax.aegis

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.agents.AgentRegistry
import com.newax.aegis.agents.SkillGuard
import com.newax.aegis.agents.SkillManager

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
private val AccentAmber = Color(0xFFF59E0B)
private val SurfaceStr = Color(0xFFE7E7E2)

private val SECTIONS = listOf("Skills", "Skill sets", "Permissions", "Approvals", "Evolution")

/**
 * The skills management surface (docs/AGENTS_DESIGN.md §skills; R13): the
 * shared skill library (import zip, enable/disable, uninstall imported),
 * named skill sets, and the PERMISSION panel — which agent may use which
 * skill (grant/revoke toggles). One skill serves many agents; a grant row is
 * the permission, and the orchestrator only advertises permitted skills.
 */
@Composable
fun SkillsScreen(padding: PaddingValues) {
    var section by remember { mutableStateOf(SECTIONS[0]) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SECTIONS.forEach { s ->
                    FilterChip(selected = section == s, onClick = { section = s }, label = { Text(s, fontSize = 12.sp) })
                }
            }
        }
        when (section) {
            "Skills" -> item { SkillsSection() }
            "Skill sets" -> item { SkillSetsSection() }
            "Permissions" -> item { PermissionsSection() }
            "Approvals" -> item { ApprovalsSection() }
            "Evolution" -> item { EvolutionSection() }
        }
    }
}

@Composable
private fun SkillsSection() {
    var skills by remember { mutableStateOf(SkillManager.skills()) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            message = SkillManager.importSkill(uri)
            skills = SkillManager.skills()
        }
    }
    val setPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            message = SkillManager.importSkillSet(uri)
            skills = SkillManager.skills()
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("${skills.size} skills — shared by all agents (permissions decide who uses what)", fontSize = 13.sp, color = TextSec)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }) { Text("Import skill (.zip)") }
                Spacer(Modifier.width(10.dp))
                Button(onClick = { setPicker.launch(arrayOf("application/json", "text/json", "*/*")) }) { Text("Import skill set (.json)") }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = { skills = SkillManager.skills() }) { Text("Refresh") }
            }
            message?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, color = if (it.startsWith("Import failed")) AccentRed else AccentGreen) }
        }
    }

    Spacer(Modifier.height(8.dp))
    skills.forEach { skill ->
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = if (skill.enabled) Surface else SurfaceMuted),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${skill.name} — v${skill.version}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri)
                    Text("${skill.category} · ${skill.skillId} · ${skill.source}", fontSize = 11.sp, color = TextTer, fontFamily = FontFamily.Monospace)
                    val flags = buildString {
                        if (skill.capability.isNotBlank()) { append("capability: ${skill.capability}") }
                        if (skill.sandboxRequired) { if (isNotEmpty()) append(" · "); append("sandbox") }
                        if (skill.requiresApproval) { if (isNotEmpty()) append(" · "); append("approval") }
                    }
                    if (flags.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(flags, fontSize = 11.sp, color = if (skill.requiresApproval || skill.sandboxRequired) AccentRed else TextTer)
                    }
                }
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { on ->
                            SkillManager.setEnabled(skill.skillId, on)
                            skills = SkillManager.skills()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White, checkedTrackColor = Primary,
                            uncheckedThumbColor = Color.White, uncheckedTrackColor = TextTer
                        )
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(skill.description, fontSize = 13.sp, color = TextSec)
                if (skill.source != "builtin") {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        SkillManager.uninstall(skill.skillId)
                        skills = SkillManager.skills()
                    }) { Text("Uninstall", fontSize = 12.sp, color = AccentRed) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SkillSetsSection() {
    var sets by remember { mutableStateOf(SkillManager.sets()) }
    var setId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Named bundles — grant/revoke skills in groups", fontSize = 13.sp, color = TextSec)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = setId, onValueChange = { setId = it }, label = { Text("Set id") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = {
                    SkillManager.createSet(setId.trim(), name.trim(), "")
                    message = "Created $name"
                    sets = SkillManager.sets(); setId = ""; name = ""
                }, enabled = setId.isNotBlank() && name.isNotBlank()) { Text("Create") }
            }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = TextSec) }
        }
    }

    Spacer(Modifier.height(8.dp))
    sets.forEach { set ->
        SetCard(set.setId, set.name, onDeleted = { sets = SkillManager.sets() })
    }
}

@Composable
private fun SetCard(setId: String, setName: String, onDeleted: () -> Unit) {
    var members by remember { mutableStateOf(SkillManager.skillsInSet(setId)) }
    var allSkills by remember { mutableStateOf(SkillManager.skills()) }
    var addId by remember { mutableStateOf("") }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(setName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    SkillManager.deleteSet(setId)
                    onDeleted()
                }) { Text("Delete set", fontSize = 12.sp, color = AccentRed) }
            }
            if (members.isEmpty()) {
                Text("Empty set", fontSize = 12.sp, color = TextTer)
            } else {
                members.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("· ${m.name} (${m.skillId})", fontSize = 12.sp, color = TextSec, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            SkillManager.removeFromSet(setId, m.skillId)
                            members = SkillManager.skillsInSet(setId)
                        }) { Text("Remove", fontSize = 11.sp, color = AccentRed) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = addId, onValueChange = { addId = it }, label = { Text("Add skill id") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val id = addId.trim()
                    if (allSkills.any { it.skillId == id }) {
                        SkillManager.addToSet(setId, id)
                        members = SkillManager.skillsInSet(setId)
                        addId = ""
                    }
                }, enabled = addId.isNotBlank()) { Text("Add", fontSize = 13.sp) }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PermissionsSection() {
    var agents by remember { mutableStateOf(AgentRegistry.agents()) }
    var selected by remember { mutableStateOf(agents.firstOrNull()?.agentId) }
    var skills by remember { mutableStateOf(SkillManager.skills()) }
    var grants by remember { mutableStateOf(selected?.let { SkillManager.grantedSkillIds(it) } ?: emptySet()) }

    if (agents.isEmpty()) {
        Text("No agents installed", fontSize = 12.sp, color = TextTer)
        return
    }
    if (selected == null || agents.none { it.agentId == selected }) {
        selected = agents.first().agentId
        grants = SkillManager.grantedSkillIds(selected!!)
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            agents.forEach { agent ->
                FilterChip(
                    selected = selected == agent.agentId,
                    onClick = {
                        selected = agent.agentId
                        grants = SkillManager.grantedSkillIds(agent.agentId)
                    },
                    label = { Text(agent.name, fontSize = 12.sp) }
                )
            }
        }
        Text("Which skills may ${agents.first { it.agentId == selected }.name} use? — grant rows are the permission", fontSize = 12.sp, color = TextTer)
        Spacer(Modifier.height(4.dp))
        skills.forEach { skill ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceMuted),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextPri)
                        Text(skill.skillId, fontSize = 11.sp, color = TextTer, fontFamily = FontFamily.Monospace)
                    }
                    Switch(
                        checked = skill.skillId in grants,
                        onCheckedChange = { on ->
                            val agentId = selected ?: return@Switch
                            if (on) SkillManager.grant(agentId, skill.skillId) else SkillManager.revoke(agentId, skill.skillId)
                            grants = SkillManager.grantedSkillIds(agentId)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White, checkedTrackColor = Primary,
                            uncheckedThumbColor = Color.White, uncheckedTrackColor = TextTer
                        )
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * The Evolution Ledger view (docs/AGENTS_DESIGN.md §evolution — RLAIF-E):
 * every skill's method variants with their Bayesian confidence, execution
 * telemetry, and lineage. Skills are dynamic mutations — this is the scoreboard
 * the exploit/explore picker reads from.
 */
@Composable
private fun EvolutionSection() {
    var tick by remember { mutableStateOf(0) }
    val ledger = remember(tick) { com.newax.aegis.agents.LearningEngine.recentLedger(500) }
    val bySkill = ledger.groupBy { it.skillId }
    val skillsByName = remember { SkillManager.skills().associateBy { it.skillId } }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Evolution ledger — ${bySkill.size} skills learning", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri)
            Spacer(Modifier.height(4.dp))
            Text("Each method's confidence is its observed success rate pulled toward 50% while evidence is thin. Methods only change the live skill after your approval.", fontSize = 12.sp, color = TextSec)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { tick++ }) { Text("Refresh", fontSize = 12.sp) }
        }
    }

    if (bySkill.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("No ledger entries yet — they appear as skills are used.", fontSize = 12.sp, color = TextTer)
        return
    }

    bySkill.forEach { (skillId, methods) ->
        Spacer(Modifier.height(8.dp))
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceMuted),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                val label = skillsByName[skillId]?.name ?: skillId
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPri)
                Text(skillId, fontSize = 11.sp, color = TextTer, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                methods.forEach { m ->
                    val pct = (m.confidence * 100).toInt()
                    val total = m.executionCount
                    val successRate = if (total > 0) (m.successCount * 100.0 / total).toInt() else 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${m.methodId} · ${m.source} · ${m.status}", fontSize = 11.sp, color = TextSec, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = { m.confidence.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                                color = if (m.status == "ACTIVE") AccentGreen else if (m.status == "REJECTED") AccentRed else AccentAmber,
                                trackColor = SurfaceStr
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("$pct% · $successRate% ($total runs)", fontSize = 10.sp, color = TextTer)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (methods.any { it.lastError.isNotBlank() }) {
                    val err = methods.first { it.lastError.isNotBlank() }.lastError
                    Text("last error: ${err.take(100)}", fontSize = 10.sp, color = AccentRed, maxLines = 1)
                }
            }
        }
    }
}

/** The HITL window: paused skill requests waiting for a human allow/deny. */
@Composable
private fun ApprovalsSection() {
    var approvals by remember { mutableStateOf(SkillGuard.pendingApprovals()) }
    var history by remember { mutableStateOf(SkillGuard.recentApprovals(20)) }
    val agentsById = remember { AgentRegistry.agents().associateBy { it.agentId } }
    val skillsById = remember { SkillManager.skills().associateBy { it.skillId } }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Paused executions — a skill request needs your decision", fontSize = 13.sp, color = TextSec)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                approvals = SkillGuard.pendingApprovals()
                history = SkillGuard.recentApprovals(20)
            }) { Text("Refresh") }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (approvals.isEmpty()) {
        Text("No pending approvals — high-impact skills pause here before they run.", fontSize = 12.sp, color = TextTer)
    } else {
        approvals.forEach { a ->
            val agentName = agentsById[a.agentId]?.name ?: a.agentId
            val skillName = skillsById[a.skillId]?.name ?: a.skillId
            val skill = skillsById[a.skillId]
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceMuted),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("$agentName → $skillName", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPri)
                    if (a.untrustedSource) {
                        Text("⚠ triggered from UNTRUSTED content (prompt-injection guard)", fontSize = 11.sp, color = AccentRed)
                    }
                    if (a.requestContext.isNotBlank()) {
                        Text("Context: ${a.requestContext}", fontSize = 12.sp, color = TextSec, maxLines = 3)
                    }
                    skill?.risks?.takeIf { it.isNotBlank() }?.let {
                        Text("Risk: $it", fontSize = 11.sp, color = TextTer)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                SkillGuard.decideApproval(a.approvalId, allow = true)
                                approvals = SkillGuard.pendingApprovals()
                                history = SkillGuard.recentApprovals(20)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text("Allow once", fontSize = 12.sp) }
                        TextButton(onClick = {
                            SkillGuard.decideApproval(a.approvalId, allow = false)
                            approvals = SkillGuard.pendingApprovals()
                            history = SkillGuard.recentApprovals(20)
                        }) { Text("Deny", fontSize = 12.sp, color = AccentRed) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    if (history.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Recent decisions", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextTer)
        history.filter { it.status != "PENDING" }.take(10).forEach { a ->
            Text("${a.status} · ${agentsById[a.agentId]?.name ?: a.agentId} → ${skillsById[a.skillId]?.name ?: a.skillId}", fontSize = 11.sp, color = TextTer)
        }
    }
}
