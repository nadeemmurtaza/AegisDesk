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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.agents.AgentRegistry
import com.newax.aegis.agents.SkillGuard
import com.newax.aegis.agents.SkillManager
import com.newax.aegis.ui.components.SkillRow
import com.newax.aegis.ui.theme.NewaxTheme

// ── Design tokens — same palette as the rest of the app ─────────────────────
private val SECTIONS = listOf(
    R.string.skills_tab_skills,
    R.string.skills_tab_sets,
    R.string.skills_tab_permissions,
    R.string.skills_tab_approvals,
    R.string.skills_tab_evolution,
)

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
                    FilterChip(selected = section == s, onClick = { section = s }, label = { Text(stringResource(s), fontSize = 12.sp) })
                }
            }
        }
        when (section) {
            R.string.skills_tab_skills -> item { SkillsSection() }
            R.string.skills_tab_sets -> item { SkillSetsSection() }
            R.string.skills_tab_permissions -> item { PermissionsSection() }
            R.string.skills_tab_approvals -> item { ApprovalsSection() }
            R.string.skills_tab_evolution -> item { EvolutionSection() }
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
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.skills_count_header, skills.size), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }) { Text(stringResource(R.string.action_import_skill_zip)) }
                Spacer(Modifier.width(10.dp))
                Button(onClick = { setPicker.launch(arrayOf("application/json", "text/json", "*/*")) }) { Text(stringResource(R.string.action_import_skill_set)) }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = { skills = SkillManager.skills() }) { Text(stringResource(R.string.action_refresh)) }
            }
            message?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, color = if (it.startsWith("Import failed")) NewaxTheme.colors.error else NewaxTheme.colors.success) }
        }
    }

    Spacer(Modifier.height(8.dp))
    skills.forEach { skill ->
        // T3.4c: the shared skill row (docs/UI_DESIGN.md §8 — SkillRow). The
        // flag colour is the caller's call — red when the skill demands approval
        // or a sandbox, neutral otherwise.
        val flags = listOfNotNull(
            if (skill.capability.isNotBlank()) context.getString(R.string.skills_flag_capability, skill.capability) else null,
            if (skill.sandboxRequired) context.getString(R.string.skills_flag_sandbox) else null,
            if (skill.requiresApproval) context.getString(R.string.skills_flag_approval) else null
        ).joinToString(" · ").ifBlank { null }
        SkillRow(
            title       = stringResource(R.string.skills_name_version, skill.name, skill.version),
            idLabel     = "${skill.category} · ${skill.skillId} · ${skill.source}",
            description = skill.description,
            enabled     = skill.enabled,
            onToggle    = { on ->
                SkillManager.setEnabled(skill.skillId, on)
                skills = SkillManager.skills()
            },
            flagsLabel  = flags,
            flagsColor  = if (skill.requiresApproval || skill.sandboxRequired) NewaxTheme.colors.error else null,
            uninstallLabel = if (skill.source != "builtin") stringResource(R.string.action_uninstall) else null,
            onUninstall = if (skill.source != "builtin") {
                {
                    SkillManager.uninstall(skill.skillId)
                    skills = SkillManager.skills()
                }
            } else null
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SkillSetsSection() {
    val context = LocalContext.current
    var sets by remember { mutableStateOf(SkillManager.sets()) }
    var setId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.skills_bundles_hint), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = setId, onValueChange = { setId = it }, label = { Text(stringResource(R.string.field_set_id)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = {
                    SkillManager.createSet(setId.trim(), name.trim(), "")
                    message = context.getString(R.string.skills_created, name)
                    sets = SkillManager.sets(); setId = ""; name = ""
                }, enabled = setId.isNotBlank() && name.isNotBlank()) { Text(stringResource(R.string.action_create)) }
            }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary) }
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
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(setName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    SkillManager.deleteSet(setId)
                    onDeleted()
                }) { Text(stringResource(R.string.action_delete_set), fontSize = 12.sp, color = NewaxTheme.colors.error) }
            }
            if (members.isEmpty()) {
                Text(stringResource(R.string.skills_empty_set), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
            } else {
                members.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("· ${m.name} (${m.skillId})", fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            SkillManager.removeFromSet(setId, m.skillId)
                            members = SkillManager.skillsInSet(setId)
                        }) { Text(stringResource(R.string.action_remove), fontSize = 11.sp, color = NewaxTheme.colors.error) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = addId, onValueChange = { addId = it }, label = { Text(stringResource(R.string.field_add_skill_id)) }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val id = addId.trim()
                    if (allSkills.any { it.skillId == id }) {
                        SkillManager.addToSet(setId, id)
                        members = SkillManager.skillsInSet(setId)
                        addId = ""
                    }
                }, enabled = addId.isNotBlank()) { Text(stringResource(R.string.action_add), fontSize = 13.sp) }
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
        Text(stringResource(R.string.skills_no_agents), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
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
        Text(stringResource(R.string.skills_grant_hint, agents.first { it.agentId == selected }.name), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
        Spacer(Modifier.height(4.dp))
        skills.forEach { skill ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surfaceMuted),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary)
                        Text(skill.skillId, fontSize = 11.sp, color = NewaxTheme.colors.textTertiary, fontFamily = FontFamily.Monospace)
                    }
                    Switch(
                        checked = skill.skillId in grants,
                        onCheckedChange = { on ->
                            val agentId = selected ?: return@Switch
                            if (on) SkillManager.grant(agentId, skill.skillId) else SkillManager.revoke(agentId, skill.skillId)
                            grants = SkillManager.grantedSkillIds(agentId)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White, checkedTrackColor = NewaxTheme.colors.textPrimary,
                            uncheckedThumbColor = Color.White, uncheckedTrackColor = NewaxTheme.colors.textTertiary
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
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.skills_ledger_header, bySkill.size), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.skills_ledger_desc), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { tick++ }) { Text(stringResource(R.string.action_refresh), fontSize = 12.sp) }
        }
    }

    if (bySkill.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.skills_no_ledger), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
        return
    }

    bySkill.forEach { (skillId, methods) ->
        Spacer(Modifier.height(8.dp))
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surfaceMuted),
            border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                val label = skillsByName[skillId]?.name ?: skillId
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary)
                Text(skillId, fontSize = 11.sp, color = NewaxTheme.colors.textTertiary, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                methods.forEach { m ->
                    val pct = (m.confidence * 100).toInt()
                    val total = m.executionCount
                    val successRate = if (total > 0) (m.successCount * 100.0 / total).toInt() else 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${m.methodId} · ${m.source} · ${m.status}", fontSize = 11.sp, color = NewaxTheme.colors.textSecondary, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = { m.confidence.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                                color = if (m.status == "ACTIVE") NewaxTheme.colors.success else if (m.status == "REJECTED") NewaxTheme.colors.error else NewaxTheme.colors.warning,
                                trackColor = NewaxTheme.colors.surfaceStrong
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("$pct% · $successRate% ($total runs)", fontSize = 10.sp, color = NewaxTheme.colors.textTertiary)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (methods.any { it.lastError.isNotBlank() }) {
                    val err = methods.first { it.lastError.isNotBlank() }.lastError
                    Text(stringResource(R.string.skills_last_error, err.take(100)), fontSize = 10.sp, color = NewaxTheme.colors.error, maxLines = 1)
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
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.skills_paused_header), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                approvals = SkillGuard.pendingApprovals()
                history = SkillGuard.recentApprovals(20)
            }) { Text(stringResource(R.string.action_refresh)) }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (approvals.isEmpty()) {
        Text(stringResource(R.string.skills_no_pending), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
    } else {
        approvals.forEach { a ->
            val agentName = agentsById[a.agentId]?.name ?: a.agentId
            val skillName = skillsById[a.skillId]?.name ?: a.skillId
            val skill = skillsById[a.skillId]
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surfaceMuted),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("$agentName → $skillName", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary)
                    if (a.untrustedSource) {
                        Text(stringResource(R.string.skills_untrusted), fontSize = 11.sp, color = NewaxTheme.colors.error)
                    }
                    if (a.requestContext.isNotBlank()) {
                        Text(stringResource(R.string.skills_context, a.requestContext), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, maxLines = 3)
                    }
                    skill?.risks?.takeIf { it.isNotBlank() }?.let {
                        Text(stringResource(R.string.skills_risk, it), fontSize = 11.sp, color = NewaxTheme.colors.textTertiary)
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
                        ) { Text(stringResource(R.string.action_allow_once), fontSize = 12.sp) }
                        TextButton(onClick = {
                            SkillGuard.decideApproval(a.approvalId, allow = false)
                            approvals = SkillGuard.pendingApprovals()
                            history = SkillGuard.recentApprovals(20)
                        }) { Text(stringResource(R.string.action_deny), fontSize = 12.sp, color = NewaxTheme.colors.error) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    if (history.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.skills_recent_decisions), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textTertiary)
        history.filter { it.status != "PENDING" }.take(10).forEach { a ->
            Text("${a.status} · ${agentsById[a.agentId]?.name ?: a.agentId} → ${skillsById[a.skillId]?.name ?: a.skillId}", fontSize = 11.sp, color = NewaxTheme.colors.textTertiary)
        }
    }
}
