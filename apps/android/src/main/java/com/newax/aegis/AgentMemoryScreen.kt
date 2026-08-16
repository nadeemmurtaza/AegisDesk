package com.newax.aegis

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.db.entity.EpisodeOutcome
import com.newax.aegis.memory.AgentMemory
import com.newax.aegis.ui.theme.NewaxTheme

// ── Design tokens — same palette as the rest of the app ─────────────────────
private val SECTIONS = listOf(
    R.string.agentmem_tab_library,
    R.string.agentmem_tab_episodic,
    R.string.agentmem_tab_scratchpad,
    R.string.agentmem_tab_handoffs,
    R.string.agentmem_tab_work_log,
)

/**
 * The three-layer hierarchical agent memory (docs/MEMORY_DESIGN.md; R13 — the
 * capability ships with its screen):
 *
 *  Library    — L1 the shared read-only Global Library. ACTIVE entries are
 *      visible to agents; PENDING_APPROVAL entries wait at the human gate
 *      (approve / reject / distill). Add-knowledge lands behind the gate.
 *  Episodic   — the "periodic" memory: chronological timeline with
 *      outcome + lesson (learning from mistakes) + a recall box that pulls
 *      only the relevant snippets an agent would consume.
 *  Scratchpad — L2 private per-agent working memory (TTL-scoped, local-only).
 *  Handoffs   — L3 shared-write structured artifacts: inbox (with ack) +
 *      outbox.
 *  Work log   — zero-work-duplication ledger: one (action, resource) done once.
 */
@Composable
fun AgentMemoryScreen(padding: PaddingValues) {
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
                    FilterChip(
                        selected = section == s,
                        onClick = { section = s },
                        label = { Text(stringResource(s), fontSize = 12.sp) }
                    )
                }
            }
        }

        when (section) {
            R.string.agentmem_tab_library -> {
                item { SectionLabel(stringResource(R.string.agentmem_section_global_library)) }
                item {
                    LibraryGateSection()
                }
                item { SectionLabel(stringResource(R.string.agentmem_section_active_knowledge)) }
                val active = AgentMemory.library()
                if (active.isEmpty()) {
                    item { EmptyChip(stringResource(R.string.agentmem_empty_knowledge)) }
                } else {
                    items(active) { entry ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text("[${entry.category}] ${entry.title}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
                                Spacer(Modifier.height(4.dp))
                                Text(entry.content, fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
                                Spacer(Modifier.height(4.dp))
                                Text(stringResource(R.string.agentmem_confidence_source, entry.confidence, entry.source), fontSize = 11.sp, color = NewaxTheme.colors.textTertiary)
                            }
                        }
                    }
                }
            }
            R.string.agentmem_tab_episodic -> {
                item { SectionLabel(stringResource(R.string.agentmem_section_episodic)) }
                item { RecordEpisodeBox() }
                item { RecallBox() }
                item { SectionLabel(stringResource(R.string.agentmem_section_timeline)) }
                val episodes = AgentMemory.recentEpisodes(40)
                if (episodes.isEmpty()) {
                    item { EmptyChip(stringResource(R.string.agentmem_empty_episodes)) }
                } else {
                    items(episodes) { ep ->
                        EpisodeCard(ep.summary, ep.agentId, ep.category, ep.outcome, ep.lesson, ep.occurredAtMs)
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
                item { SectionLabel(stringResource(R.string.agentmem_section_lessons)) }
                val lessons = AgentMemory.lessonsLearned(20)
                if (lessons.isEmpty()) {
                    item { EmptyChip(stringResource(R.string.agentmem_empty_lessons)) }
                } else {
                    items(lessons) { ep ->
                        EpisodeCard(ep.summary, ep.agentId, ep.category, ep.outcome, ep.lesson, ep.occurredAtMs)
                    }
                }
            }
            R.string.agentmem_tab_scratchpad -> {
                item { SectionLabel(stringResource(R.string.agentmem_section_scratchpad)) }
                item { ScratchpadSection() }
            }
            R.string.agentmem_tab_handoffs -> {
                item { SectionLabel(stringResource(R.string.agentmem_section_handoff_inbox)) }
                item { HandoffInboxSection() }
                item { SectionLabel(stringResource(R.string.agentmem_section_send_handoff)) }
                item { CreateHandoffBox() }
                item { SectionLabel(stringResource(R.string.agentmem_section_handoff_outbox)) }
                val outbox = AgentMemory.handoffOutbox("assistant")
                if (outbox.isEmpty()) {
                    item { EmptyChip(stringResource(R.string.agentmem_empty_outbox)) }
                } else {
                    items(outbox) { h ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(stringResource(R.string.agentmem_outbox_to, h.toAgent, h.task), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
                                Spacer(Modifier.height(4.dp))
                                Text(h.summary, fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text("${h.status} · ${java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                                ).format(java.util.Date(h.createdAtMs))}", fontSize = 11.sp, color = NewaxTheme.colors.textTertiary)
                            }
                        }
                    }
                }
            }
            R.string.agentmem_tab_work_log -> {
                item { SectionLabel(stringResource(R.string.agentmem_section_work_log)) }
                item { WorkLogSection() }
            }
        }
    }
}

/** The human-in-the-loop gate: submit → PENDING → approve/reject/distill. */
@Composable
private fun LibraryGateSection() {
    val context = LocalContext.current
    var category by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var approvals by remember { mutableStateOf(AgentMemory.pendingApprovals()) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.agentmem_add_knowledge_hint), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text(stringResource(R.string.field_category)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.field_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text(stringResource(R.string.field_content)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (title.isBlank() || content.isBlank()) {
                            message = context.getString(R.string.agentmem_err_title_content_required)
                        } else {
                            AgentMemory.submitKnowledge(category.ifBlank { "general" }, title.trim(), content.trim())
                            message = context.getString(R.string.agentmem_submitted_gate)
                            approvals = AgentMemory.pendingApprovals()
                            category = ""; title = ""; content = ""
                        }
                    }
                ) { Text(stringResource(R.string.action_submit)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val resolved = AgentMemory.distill()
                        message = context.getString(R.string.agentmem_bg_pass, resolved)
                        approvals = AgentMemory.pendingApprovals()
                    }
                ) { Text(stringResource(R.string.action_distill)) }
            }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary) }
        }
    }

    Spacer(Modifier.height(8.dp))
    if (approvals.isEmpty()) {
        Text(stringResource(R.string.agentmem_no_pending_approvals), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
    } else {
        approvals.forEach { entry ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surfaceMuted),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("[${entry.category}] ${entry.title}", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(entry.content, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                AgentMemory.approveKnowledge(entry.entryId)
                                approvals = AgentMemory.pendingApprovals()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text(stringResource(R.string.action_approve), fontSize = 12.sp) }
                        TextButton(
                            onClick = {
                                AgentMemory.rejectKnowledge(entry.entryId)
                                approvals = AgentMemory.pendingApprovals()
                            }
                        ) { Text(stringResource(R.string.action_reject), fontSize = 12.sp, color = NewaxTheme.colors.error) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Record an episode (with outcome + lesson) — the producer surface. */
@Composable
private fun RecordEpisodeBox() {
    val context = LocalContext.current
    var agent by remember { mutableStateOf("assistant") }
    var category by remember { mutableStateOf("task") }
    var summary by remember { mutableStateOf("") }
    var lesson by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.agentmem_record_hint), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = summary, onValueChange = { summary = it }, label = { Text(stringResource(R.string.field_what_happened)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = lesson, onValueChange = { lesson = it }, label = { Text(stringResource(R.string.field_lesson)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (summary.isBlank()) return@Button
                        AgentMemory.recordEpisode(
                            agent.trim().ifBlank { "assistant" }, category.trim().ifBlank { "task" }, summary.trim(),
                            if (lesson.isBlank()) EpisodeOutcome.OBSERVATION else EpisodeOutcome.FAILURE, lesson.trim()
                        )
                        message = context.getString(R.string.agentmem_episode_recorded)
                        summary = ""; lesson = ""
                    }
                ) { Text(stringResource(R.string.action_record), fontSize = 13.sp) }
                OutlinedTextField(value = agent, onValueChange = { agent = it }, label = { Text(stringResource(R.string.field_agent)) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary) }
        }
    }
}

/** The cost lever — pull only the tiny relevant snippets, not whole histories. */
@Composable
private fun RecallBox() {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.agentmem_recall_hint), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.field_query)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = { results = AgentMemory.recall(query.trim(), 5) }, enabled = query.isNotBlank()) { Text(stringResource(R.string.action_recall)) }
            if (results.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                results.forEach { r -> Text(r, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, fontFamily = FontFamily.Monospace); Spacer(Modifier.height(4.dp)) }
            }
        }
    }
}

@Composable
private fun EpisodeCard(summary: String, agentId: String, category: String, outcome: String, lesson: String, atMs: Long) {
    val color = when (outcome) {
        EpisodeOutcome.SUCCESS -> NewaxTheme.colors.success
        EpisodeOutcome.FAILURE -> NewaxTheme.colors.error
        else -> NewaxTheme.colors.warning
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(color))
                Spacer(Modifier.width(8.dp))
                Text("$agentId · $category · $outcome", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary)
            }
            Spacer(Modifier.height(4.dp))
            Text(summary, fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
            if (lesson.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.agentmem_lesson, lesson), fontSize = 12.sp, color = if (outcome == EpisodeOutcome.FAILURE) NewaxTheme.colors.error else NewaxTheme.colors.success)
            }
            Spacer(Modifier.height(4.dp))
            Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(java.util.Date(atMs)), fontSize = 11.sp, color = NewaxTheme.colors.textTertiary)
        }
    }
}

/** L2 — private per-agent scratchpad with TTL. */
@Composable
private fun ScratchpadSection() {
    var agentId by remember { mutableStateOf("assistant") }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var ttl by remember { mutableStateOf("3600000") }
    var entries by remember { mutableStateOf(AgentMemory.scratchpadFor("assistant")) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.agentmem_scratchpad_hint), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = agentId, onValueChange = { agentId = it }, label = { Text(stringResource(R.string.field_agent)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(stringResource(R.string.field_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(stringResource(R.string.field_value)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = ttl, onValueChange = { ttl = it }, label = { Text(stringResource(R.string.field_ttl)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        AgentMemory.scratchpadPut(agentId.trim().ifBlank { "assistant" }, key.trim(), value, ttl.toLongOrNull() ?: 0L)
                        entries = AgentMemory.scratchpadFor(agentId.trim().ifBlank { "assistant" })
                        key = ""; value = ""
                    },
                    enabled = key.isNotBlank()
                ) { Text(stringResource(R.string.action_save)) }
                OutlinedButton(onClick = {
                    AgentMemory.scratchpadClear(agentId.trim().ifBlank { "assistant" })
                    entries = AgentMemory.scratchpadFor(agentId.trim().ifBlank { "assistant" })
                }) { Text(stringResource(R.string.action_clear_all), fontSize = 13.sp) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (entries.isEmpty()) {
        Text(stringResource(R.string.agentmem_no_scratchpad), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
    } else {
        entries.forEach { e ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surfaceMuted),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.key, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary, fontFamily = FontFamily.Monospace)
                        Text(e.value, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = {
                        AgentMemory.scratchpadDelete(e.agentId, e.key)
                        entries = AgentMemory.scratchpadFor(e.agentId)
                    }) { Text(stringResource(R.string.action_delete), fontSize = 12.sp, color = NewaxTheme.colors.error) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** L3 — the producer surface: write a clean artifact and pass the pointer. */
@Composable
private fun CreateHandoffBox() {
    val context = LocalContext.current
    var to by remember { mutableStateOf("analyst") }
    var task by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.agentmem_handoff_hint), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text(stringResource(R.string.field_to_agent)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = task, onValueChange = { task = it }, label = { Text(stringResource(R.string.field_task)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = summary, onValueChange = { summary = it }, label = { Text(stringResource(R.string.field_summary_artifact)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (task.isBlank() || summary.isBlank()) return@Button
                    AgentMemory.createHandoff("assistant", to.trim().ifBlank { "analyst" }, task.trim(), summary.trim())
                    message = context.getString(R.string.agentmem_handoff_written, to.trim())
                    task = ""; summary = ""
                }
            ) { Text(stringResource(R.string.action_send_handoff), fontSize = 13.sp) }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary) }
        }
    }
}

/** L3 — shared-write handoffs: consumer acks the artifact. */
@Composable
private fun HandoffInboxSection() {
    val context = LocalContext.current
    var agent by remember { mutableStateOf("assistant") }
    var inbox by remember { mutableStateOf(AgentMemory.handoffInbox("assistant")) }
    var message by remember { mutableStateOf<String?>(null) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = agent, onValueChange = { agent = it }, label = { Text(stringResource(R.string.field_as_agent)) }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    inbox = AgentMemory.handoffInbox(agent.trim().ifBlank { "assistant" })
                    message = null
                }) { Text(stringResource(R.string.action_refresh)) }
            }
        }
    }
    message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary) }
    Spacer(Modifier.height(8.dp))
    if (inbox.isEmpty()) {
        Text(stringResource(R.string.agentmem_no_handoffs), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
    } else {
        inbox.forEach { h ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.agentmem_handoff_from, h.fromAgent, h.task), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(h.summary, fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
                    if (h.refId.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.agentmem_handoff_ref, h.refId), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            AgentMemory.ackHandoff(h.handoffId)
                            inbox = AgentMemory.handoffInbox(agent.trim().ifBlank { "assistant" })
                            message = context.getString(R.string.agentmem_acked, h.handoffId.take(8))
                        }
                    ) { Text(stringResource(R.string.action_ack_pickup), fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Zero-work-duplication ledger. */
@Composable
private fun WorkLogSection() {
    val context = LocalContext.current
    var action by remember { mutableStateOf("") }
    var resource by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(AgentMemory.recentWork(20)) }
    var message by remember { mutableStateOf<String?>(null) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.agentmem_worklog_hint), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = action, onValueChange = { action = it }, label = { Text(stringResource(R.string.field_action)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = resource, onValueChange = { resource = it }, label = { Text(stringResource(R.string.field_resource)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val claimed = AgentMemory.claimWork(action.trim(), resource.trim(), "assistant")
                        message = if (claimed) context.getString(R.string.agentmem_claimed)
                        else if (AgentMemory.isWorkDone(action.trim(), resource.trim())) context.getString(R.string.agentmem_already_done)
                        else context.getString(R.string.agentmem_claimed_previously)
                        entries = AgentMemory.recentWork(20)
                    },
                    enabled = action.isNotBlank() && resource.isNotBlank()
                ) { Text(stringResource(R.string.action_claim)) }
                OutlinedButton(onClick = {
                    AgentMemory.completeWork(action.trim(), resource.trim())
                    message = context.getString(R.string.agentmem_marked_done)
                    entries = AgentMemory.recentWork(20)
                }) { Text(stringResource(R.string.action_mark_done)) }
            }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary) }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (entries.isEmpty()) {
        Text(stringResource(R.string.agentmem_nothing_logged), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
    } else {
        entries.forEach { w ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surfaceMuted),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (w.status == "DONE") NewaxTheme.colors.success else NewaxTheme.colors.warning))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${w.action} · ${w.resource}", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${w.status} · ${w.agentId}", fontSize = 11.sp, color = NewaxTheme.colors.textTertiary)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textTertiary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

@Composable
private fun EmptyChip(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(NewaxTheme.colors.surfaceMuted)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 13.sp, color = NewaxTheme.colors.textTertiary)
    }
}
