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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.db.entity.EpisodeOutcome
import com.newax.aegis.memory.AgentMemory

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

private val SECTIONS = listOf("Library", "Episodic", "Scratchpad", "Handoffs", "Work log")

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
                        label = { Text(s, fontSize = 12.sp) }
                    )
                }
            }
        }

        when (section) {
            "Library" -> {
                item { SectionLabel("Global Library — read-only for agents") }
                item {
                    LibraryGateSection()
                }
                item { SectionLabel("Active knowledge") }
                val active = AgentMemory.library()
                if (active.isEmpty()) {
                    item { EmptyChip("No approved knowledge yet — submit below and approve it") }
                } else {
                    items(active) { entry ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text("[${entry.category}] ${entry.title}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri)
                                Spacer(Modifier.height(4.dp))
                                Text(entry.content, fontSize = 13.sp, color = TextSec)
                                Spacer(Modifier.height(4.dp))
                                Text("confidence ${entry.confidence} · source ${entry.source}", fontSize = 11.sp, color = TextTer)
                            }
                        }
                    }
                }
            }
            "Episodic" -> {
                item { SectionLabel("Episodic memory — outcome + lesson") }
                item { RecordEpisodeBox() }
                item { RecallBox() }
                item { SectionLabel("Timeline (newest first)") }
                val episodes = AgentMemory.recentEpisodes(40)
                if (episodes.isEmpty()) {
                    item { EmptyChip("No episodes yet — record one from any agent") }
                } else {
                    items(episodes) { ep ->
                        EpisodeCard(ep.summary, ep.agentId, ep.category, ep.outcome, ep.lesson, ep.occurredAtMs)
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
                item { SectionLabel("Lessons learned (FAILURE episodes)") }
                val lessons = AgentMemory.lessonsLearned(20)
                if (lessons.isEmpty()) {
                    item { EmptyChip("No lessons yet — failures will show up here") }
                } else {
                    items(lessons) { ep ->
                        EpisodeCard(ep.summary, ep.agentId, ep.category, ep.outcome, ep.lesson, ep.occurredAtMs)
                    }
                }
            }
            "Scratchpad" -> {
                item { SectionLabel("Agent scratchpad — private, isolated, local-only") }
                item { ScratchpadSection() }
            }
            "Handoffs" -> {
                item { SectionLabel("Handoff inbox (L3 shared write)") }
                item { HandoffInboxSection() }
                item { SectionLabel("Send a handoff") }
                item { CreateHandoffBox() }
                item { SectionLabel("Handoff outbox") }
                val outbox = AgentMemory.handoffOutbox("assistant")
                if (outbox.isEmpty()) {
                    item { EmptyChip("Nothing sent yet") }
                } else {
                    items(outbox) { h ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text("→ ${h.toAgent}: ${h.task}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri)
                                Spacer(Modifier.height(4.dp))
                                Text(h.summary, fontSize = 13.sp, color = TextSec, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text("${h.status} · ${java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                                ).format(java.util.Date(h.createdAtMs))}", fontSize = 11.sp, color = TextTer)
                            }
                        }
                    }
                }
            }
            "Work log" -> {
                item { SectionLabel("Work log — zero work duplication") }
                item { WorkLogSection() }
            }
        }
    }
}

/** The human-in-the-loop gate: submit → PENDING → approve/reject/distill. */
@Composable
private fun LibraryGateSection() {
    var category by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var approvals by remember { mutableStateOf(AgentMemory.pendingApprovals()) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Add knowledge (lands PENDING — agents can't see it until approved)", fontSize = 13.sp, color = TextSec)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("Content") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (title.isBlank() || content.isBlank()) {
                            message = "Title and content are required"
                        } else {
                            AgentMemory.submitKnowledge(category.ifBlank { "general" }, title.trim(), content.trim())
                            message = "Submitted — waiting at the human gate"
                            approvals = AgentMemory.pendingApprovals()
                            category = ""; title = ""; content = ""
                        }
                    }
                ) { Text("Submit") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val resolved = AgentMemory.distill()
                        message = "Background pass: $resolved touched (conflicts, consolidation, decay)"
                        approvals = AgentMemory.pendingApprovals()
                    }
                ) { Text("Distill") }
            }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = TextSec) }
        }
    }

    Spacer(Modifier.height(8.dp))
    if (approvals.isEmpty()) {
        Text("No pending approvals", fontSize = 12.sp, color = TextTer)
    } else {
        approvals.forEach { entry ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceMuted),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("[${entry.category}] ${entry.title}", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextPri)
                    Spacer(Modifier.height(2.dp))
                    Text(entry.content, fontSize = 12.sp, color = TextSec)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                AgentMemory.approveKnowledge(entry.entryId)
                                approvals = AgentMemory.pendingApprovals()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text("Approve", fontSize = 12.sp) }
                        TextButton(
                            onClick = {
                                AgentMemory.rejectKnowledge(entry.entryId)
                                approvals = AgentMemory.pendingApprovals()
                            }
                        ) { Text("Reject", fontSize = 12.sp, color = AccentRed) }
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
    var agent by remember { mutableStateOf("assistant") }
    var category by remember { mutableStateOf("task") }
    var summary by remember { mutableStateOf("") }
    var lesson by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Record (FAILURE episodes carry a lesson the mesh inherits)", fontSize = 13.sp, color = TextSec)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = summary, onValueChange = { summary = it }, label = { Text("What happened") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = lesson, onValueChange = { lesson = it }, label = { Text("Lesson (empty = plain observation)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (summary.isBlank()) return@Button
                        AgentMemory.recordEpisode(
                            agent.trim().ifBlank { "assistant" }, category.trim().ifBlank { "task" }, summary.trim(),
                            if (lesson.isBlank()) EpisodeOutcome.OBSERVATION else EpisodeOutcome.FAILURE, lesson.trim()
                        )
                        message = "Episode recorded + journaled into the mesh"
                        summary = ""; lesson = ""
                    }
                ) { Text("Record", fontSize = 13.sp) }
                OutlinedTextField(value = agent, onValueChange = { agent = it }, label = { Text("Agent") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = TextSec) }
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
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Recall (what an agent pulls — snippets, not chat histories)", fontSize = 13.sp, color = TextSec)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Query") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = { results = AgentMemory.recall(query.trim(), 5) }, enabled = query.isNotBlank()) { Text("Recall") }
            if (results.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                results.forEach { r -> Text(r, fontSize = 12.sp, color = TextSec, fontFamily = FontFamily.Monospace); Spacer(Modifier.height(4.dp)) }
            }
        }
    }
}

@Composable
private fun EpisodeCard(summary: String, agentId: String, category: String, outcome: String, lesson: String, atMs: Long) {
    val color = when (outcome) {
        EpisodeOutcome.SUCCESS -> AccentGreen
        EpisodeOutcome.FAILURE -> AccentRed
        else -> AccentAmber
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(color))
                Spacer(Modifier.width(8.dp))
                Text("$agentId · $category · $outcome", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextPri)
            }
            Spacer(Modifier.height(4.dp))
            Text(summary, fontSize = 13.sp, color = TextSec)
            if (lesson.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("lesson: $lesson", fontSize = 12.sp, color = if (outcome == EpisodeOutcome.FAILURE) AccentRed else AccentGreen)
            }
            Spacer(Modifier.height(4.dp))
            Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(java.util.Date(atMs)), fontSize = 11.sp, color = TextTer)
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
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Isolated per agent — never syncs, TTL expires it", fontSize = 13.sp, color = TextSec)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = agentId, onValueChange = { agentId = it }, label = { Text("Agent") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = ttl, onValueChange = { ttl = it }, label = { Text("TTL ms (0 = forever)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        AgentMemory.scratchpadPut(agentId.trim().ifBlank { "assistant" }, key.trim(), value, ttl.toLongOrNull() ?: 0L)
                        entries = AgentMemory.scratchpadFor(agentId.trim().ifBlank { "assistant" })
                        key = ""; value = ""
                    },
                    enabled = key.isNotBlank()
                ) { Text("Save") }
                OutlinedButton(onClick = {
                    AgentMemory.scratchpadClear(agentId.trim().ifBlank { "assistant" })
                    entries = AgentMemory.scratchpadFor(agentId.trim().ifBlank { "assistant" })
                }) { Text("Clear all", fontSize = 13.sp) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (entries.isEmpty()) {
        Text("No scratchpad entries", fontSize = 12.sp, color = TextTer)
    } else {
        entries.forEach { e ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceMuted),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.key, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextPri, fontFamily = FontFamily.Monospace)
                        Text(e.value, fontSize = 12.sp, color = TextSec, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = {
                        AgentMemory.scratchpadDelete(e.agentId, e.key)
                        entries = AgentMemory.scratchpadFor(e.agentId)
                    }) { Text("Delete", fontSize = 12.sp, color = AccentRed) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** L3 — the producer surface: write a clean artifact and pass the pointer. */
@Composable
private fun CreateHandoffBox() {
    var to by remember { mutableStateOf("analyst") }
    var task by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Clean summary + pointer — the consumer reads this, not your scratchpad", fontSize = 13.sp, color = TextSec)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text("To agent") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = task, onValueChange = { task = it }, label = { Text("Task") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = summary, onValueChange = { summary = it }, label = { Text("Summary artifact") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (task.isBlank() || summary.isBlank()) return@Button
                    AgentMemory.createHandoff("assistant", to.trim().ifBlank { "analyst" }, task.trim(), summary.trim())
                    message = "Handoff written + journaled — ${to.trim()} sees it in its inbox"
                    task = ""; summary = ""
                }
            ) { Text("Send handoff", fontSize = 13.sp) }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = TextSec) }
        }
    }
}

/** L3 — shared-write handoffs: consumer acks the artifact. */
@Composable
private fun HandoffInboxSection() {
    var agent by remember { mutableStateOf("assistant") }
    var inbox by remember { mutableStateOf(AgentMemory.handoffInbox("assistant")) }
    var message by remember { mutableStateOf<String?>(null) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = agent, onValueChange = { agent = it }, label = { Text("As agent") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    inbox = AgentMemory.handoffInbox(agent.trim().ifBlank { "assistant" })
                    message = null
                }) { Text("Refresh") }
            }
        }
    }
    message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = TextSec) }
    Spacer(Modifier.height(8.dp))
    if (inbox.isEmpty()) {
        Text("No pending handoffs for this agent", fontSize = 12.sp, color = TextTer)
    } else {
        inbox.forEach { h ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("from ${h.fromAgent}: ${h.task}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri)
                    Spacer(Modifier.height(4.dp))
                    Text(h.summary, fontSize = 13.sp, color = TextSec)
                    if (h.refId.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("ref: ${h.refId}", fontSize = 12.sp, color = TextTer, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            AgentMemory.ackHandoff(h.handoffId)
                            inbox = AgentMemory.handoffInbox(agent.trim().ifBlank { "assistant" })
                            message = "Acked ${h.handoffId.take(8)} — the ack propagates to the sender"
                        }
                    ) { Text("Ack & pick up", fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Zero-work-duplication ledger. */
@Composable
private fun WorkLogSection() {
    var action by remember { mutableStateOf("") }
    var resource by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(AgentMemory.recentWork(20)) }
    var message by remember { mutableStateOf<String?>(null) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("One (action, resource) done once — the swarm skips finished work", fontSize = 13.sp, color = TextSec)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = action, onValueChange = { action = it }, label = { Text("Action (scrape, fix, import…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = resource, onValueChange = { resource = it }, label = { Text("Resource (URL, file, package…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val claimed = AgentMemory.claimWork(action.trim(), resource.trim(), "assistant")
                        message = if (claimed) "Claimed — mark done when finished"
                        else if (AgentMemory.isWorkDone(action.trim(), resource.trim())) "Already DONE — no duplication"
                        else "Claimed previously"
                        entries = AgentMemory.recentWork(20)
                    },
                    enabled = action.isNotBlank() && resource.isNotBlank()
                ) { Text("Claim") }
                OutlinedButton(onClick = {
                    AgentMemory.completeWork(action.trim(), resource.trim())
                    message = "Marked done"
                    entries = AgentMemory.recentWork(20)
                }) { Text("Mark done") }
            }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = TextSec) }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (entries.isEmpty()) {
        Text("Nothing logged yet", fontSize = 12.sp, color = TextTer)
    } else {
        entries.forEach { w ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceMuted),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (w.status == "DONE") AccentGreen else AccentAmber))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${w.action} · ${w.resource}", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextPri, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${w.status} · ${w.agentId}", fontSize = 11.sp, color = TextTer)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextTer,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

@Composable
private fun EmptyChip(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(SurfaceMuted)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 13.sp, color = TextTer)
    }
}
