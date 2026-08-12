package com.newax.aegis

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.newax.aegis.engine.audit.ExecutionAuditEntry
import com.newax.aegis.engine.audit.ExecutionAuditHolder
import com.newax.aegis.engine.audit.ExecutionCsv
import com.newax.aegis.engine.audit.RunOutcome
import java.io.File
import com.newax.aegis.engine.bus.AegisEvent
import com.newax.aegis.engine.bus.AegisEventBus
import com.newax.aegis.engine.execution.GoalExecutor
import com.newax.aegis.engine.intelligence.Goal
import com.newax.aegis.engine.intelligence.GoalPlanner
import com.newax.aegis.engine.intelligence.PlanResult
import com.newax.aegis.engine.intelligence.SkillRegistry
import com.newax.aegis.engine.intelligence.TaskFailureKind
import com.newax.aegis.engine.intelligence.TaskGraph
import com.newax.aegis.engine.intelligence.TaskStatus
import com.newax.aegis.engine.state.GoalState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Design tokens (REFINED_THEME.md) ────────────────────────────────────────
private val Surface      = Color(0xFFFFFFFF)
private val SurfaceMuted = Color(0xFFF2F2EF)
private val TextPri      = Color(0xFF1B1B1A)
private val TextSec      = Color(0xFF686864)
private val TextTer      = Color(0xFF8D8D87)
private val Border       = Color(0xFFD8D8D3)

private val ReadyCol    = Color(0xFF22C55E)
private val WarnCol     = Color(0xFFF59E0B)
private val ErrorCol    = Color(0xFFEF4444)
private val MutedCol    = Color(0xFF94A3B8)

private data class GoalRow(
    val goal: Goal,
    val state: GoalState?,
    val plan: PlanResult?,
    val graph: TaskGraph?,
)

private fun GoalState.label(): String = when (this) {
    GoalState.OPEN       -> "Open"
    GoalState.ACTIVE     -> "Active"
    GoalState.BLOCKED    -> "Blocked"
    GoalState.COMPLETED  -> "Completed"
    GoalState.ABANDONED  -> "Abandoned"
}

private fun GoalState.dotColor(): Color = when (this) {
    GoalState.OPEN       -> MutedCol
    GoalState.ACTIVE     -> ReadyCol
    GoalState.BLOCKED    -> ErrorCol
    GoalState.COMPLETED  -> ReadyCol
    GoalState.ABANDONED  -> MutedCol
}

private fun readGoalSnapshot(): List<GoalRow> =
    GoalPlanner.allGoals()
        .sortedByDescending { it.priority }
        .map { goal ->
            GoalRow(
                goal  = goal,
                state = GoalPlanner.getState(goal.id),
                plan  = GoalPlanner.planOf(goal.id),
                graph = GoalPlanner.getGraph(goal.id),
            )
        }

/**
 * Goal / plan screen — the UI face of GoalPlanner. Every goal the planner knows
 * is listed with its state and plan pre-flight. A goal whose skills exist but
 * whose platform capabilities are not ready shows exactly which capabilities
 * are missing, so the user understands why it is blocked (and can fix it via
 * the Capabilities screen). States: empty (no goals yet), content.
 */
@Composable
fun GoalsScreen(
    padding: PaddingValues,
    /**
     * Fired from a policy-blocked task with the policy action class that was
     * refused (or null when unknown) — jumps the user to that class's row in
     * Policy modes, highlighting it; null falls back to the section top.
     */
    onOpenPolicyModes: (String?) -> Unit = {}
) {
    var refreshKey by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf("") }
    val rows = remember(refreshKey) { readGoalSnapshot() }
    val runs = remember(refreshKey) { ExecutionAuditHolder.recent(6) }

    // Live updates: task transitions, blocked goals, and completions re-read the snapshot.
    LaunchedEffect(Unit) {
        AegisEventBus.flow
            .filter { it is AegisEvent.TaskUpdated || it is AegisEvent.GoalBlocked || it is AegisEvent.GoalCompleted }
            .collect { refreshKey++ }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding      = PaddingValues(vertical = 12.dp)
    ) {
        // ── New goal input ─────────────────────────────────────────────────
        item {
            Card(
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = Surface),
                border    = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "New goal",
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 15.sp,
                        color      = TextPri
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value       = draft,
                        onValueChange = { draft = it },
                        placeholder  = { Text("e.g. send a message to Ali tomorrow", fontSize = 14.sp, color = TextTer) },
                        singleLine   = true,
                        shape        = RoundedCornerShape(12.dp),
                        colors       = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TextSec,
                            unfocusedBorderColor = Border,
                            focusedContainerColor = Surface,
                            unfocusedContainerColor = Surface
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions  = KeyboardActions(onDone = { addGoal(draft) { draft = ""; refreshKey++ } }),
                        trailingIcon = {
                            IconButton(
                                enabled = draft.isNotBlank(),
                                onClick  = { addGoal(draft) { draft = ""; refreshKey++ } }
                            ) {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = "Plan goal",
                                    tint = if (draft.isNotBlank()) TextPri else TextTer
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Aegis decomposes the goal, then checks each skill's capabilities against the platform registry before calling it feasible.",
                        fontSize = 12.sp,
                        color    = TextTer,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        // ── Header / summary ───────────────────────────────────────────────
        if (rows.isNotEmpty()) item {
            Card(
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = Surface),
                border    = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${rows.size} ${if (rows.size == 1) "goal" else "goals"}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 15.sp,
                            color      = TextPri
                        )
                        Spacer(Modifier.height(2.dp))
                        val blocked = rows.count { it.plan != null && !it.plan.feasible }
                        Text(
                            when {
                                blocked == 0 -> "All plans feasible · nothing blocked"
                                else -> "$blocked blocked by platform capabilities"
                            },
                            fontSize = 13.sp,
                            color    = if (blocked == 0) TextSec else WarnCol
                        )
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Refresh goals",
                            tint = TextSec
                        )
                    }
                }
            }
        }

        // ── States ─────────────────────────────────────────────────────────
        if (rows.isEmpty()) item {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = TextTer, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("No goals yet", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextSec)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Type a goal above to see Aegis's plan — and why it might be blocked.",
                        fontSize = 13.sp,
                        color    = TextTer,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
        } else {
            items(rows, key = { it.goal.id }) { row -> GoalCard(row, onOpenPolicyModes) { refreshKey++ } }
        }

        // ── Recent runs (execution audit trail, Track A8) ────────────────
        item { RecentRunsSection(runs) }
    }
}

private fun addGoal(text: String, onAdded: () -> Unit) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    GoalPlanner.plan(trimmed)
    onAdded()
}

@Composable
private fun GoalCard(row: GoalRow, onOpenPolicyModes: (String?) -> Unit, onChanged: () -> Unit) {
    val plan  = row.plan
    val graph = row.graph
    val tasks = graph?.tasks.orEmpty()
    val done  = tasks.count { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.SKIPPED }
    val blocked = plan != null && !plan.feasible
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, if (blocked) WarnCol.copy(alpha = 0.5f) else Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // ── Headline: dot · description · state chip ──────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(row.state?.dotColor() ?: MutedCol)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    row.goal.description,
                    fontWeight = FontWeight.Medium,
                    fontSize   = 15.sp,
                    color      = TextPri,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                StateChip(row.state)
            }

            // ── Meta: intent · priority · tasks ───────────────────────────
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(row.goal.intent, TextSec)
                Tag("Priority ${row.goal.priority}", TextSec)
                if (tasks.isNotEmpty()) Tag("${done}/${tasks.size} tasks done", if (done == tasks.size) ReadyCol else TextSec)
            }

            // ── Feasibility / block reasons ────────────────────────────────
            when {
                plan == null -> {
                    Spacer(Modifier.height(10.dp))
                    BlockBanner(
                        iconColor = MutedCol,
                        title     = "No plan yet",
                        body      = "This goal has not been through the planner pre-flight."
                    )
                }
                plan != null && !plan.feasible -> {
                    Spacer(Modifier.height(10.dp))
                    BlockBanner(
                        iconColor = WarnCol,
                        title     = "Blocked — the platform can't run this yet",
                        body      = null
                    )
                    // The core ask: name the missing capabilities the user can fix.
                    if (plan.missingCapabilities.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            plan.missingCapabilities.forEach { cap ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(WarnCol)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        cap,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize   = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color      = TextPri
                                    )
                                }
                            }
                            Text(
                                "Enable the matching capability on the Capabilities screen, then re-plan.",
                                fontSize = 12.sp,
                                color    = TextTer
                            )
                        }
                    }
                    if (plan.missingSkills.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Missing skills", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSec)
                            plan.missingSkills.forEach { skill ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(ErrorCol)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(skill, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = TextPri)
                                }
                            }
                        }
                    }
                    if (plan.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            plan.warnings.forEach { warning ->
                                Text(
                                    "· $warning",
                                    fontSize   = 12.sp,
                                    color      = TextTer,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
                else -> {
                    Spacer(Modifier.height(10.dp))
                    BlockBanner(
                        iconColor = ReadyCol,
                        title     = "Plan ready · ${tasks.size} tasks",
                        body      = "No platform blockers — every skill resolves through a ready capability."
                    )
                }
            }

            // ── Live task state: running task + failed reasons ─────────────
            tasks.firstOrNull { it.status == TaskStatus.RUNNING }?.let { runningTask ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = WarnCol)
                    Spacer(Modifier.width(8.dp))
                    Text("Running: ${runningTask.description}", fontSize = 12.sp, color = TextSec)
                }
            }
            // Policy-blocked tasks first, with distinct amber treatment: the refusal
            // is actionable (change the mode or run from chat), so the task gets its
            // own label and a direct path into the Policy modes section.
            tasks.filter {
                it.status == TaskStatus.FAILED && it.failureKind == TaskFailureKind.POLICY
            }.forEach { failed ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(WarnCol)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(failed.description, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPri)
                            Spacer(Modifier.width(8.dp))
                            PolicyTag()
                        }
                        failed.result?.let { result ->
                            Spacer(Modifier.height(2.dp))
                            Text(result, fontSize = 12.sp, color = TextTer, lineHeight = 16.sp)
                        }
                        Spacer(Modifier.height(2.dp))
                        ActionButton("Policy modes", WarnCol) {
                            onOpenPolicyModes(policyActionClassFor(failed.skillId))
                        }
                    }
                }
            }
            tasks.filter {
                it.status == TaskStatus.FAILED && it.failureKind != TaskFailureKind.POLICY
            }.forEach { failed ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(ErrorCol)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(failed.description, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPri)
                        failed.result?.let { result ->
                            Spacer(Modifier.height(2.dp))
                            Text(result, fontSize = 12.sp, color = TextTer, lineHeight = 16.sp)
                        }
                    }
                }
            }

            // ── Lifecycle actions ──────────────────────────────────────────
            val state = row.state
            if (state != null && state != GoalState.COMPLETED && state != GoalState.ABANDONED) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val running = tasks.any { it.status == TaskStatus.RUNNING }
                    if ((state == GoalState.OPEN || state == GoalState.BLOCKED) && !running) {
                        ActionButton(if (state == GoalState.BLOCKED) "Retry" else "Activate", ReadyCol) {
                            scope.launch {
                                GoalExecutor.run(row.goal.id, context)
                                onChanged()
                            }
                        }
                    }
                    ActionButton("Abandon", TextTer) {
                        GoalPlanner.abandon(row.goal.id)
                        onChanged()
                    }
                }
            }
        }
    }
}

@Composable
private fun StateChip(state: GoalState?) {
    val color = state?.dotColor() ?: MutedCol
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            state?.label() ?: "Unknown",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(SurfaceMuted)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun BlockBanner(iconColor: Color, title: String, body: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(iconColor.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(iconColor)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPri)
            if (body != null) {
                Spacer(Modifier.height(2.dp))
                Text(body, fontSize = 12.sp, color = TextSec, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun RecentRunsSection(runs: List<ExecutionAuditEntry>) {
    if (runs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Recent runs",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = TextPri,
                modifier = Modifier.weight(1f)
            )
            AuditExportControls()
        }
        runs.forEach { run -> RunCard(run) }
    }
}

/** Where the last execution-audit CSV export ended: nothing yet, saved, or failed. */
private sealed interface AuditExportStatus {
    data object Idle : AuditExportStatus
    data object Done : AuditExportStatus
    data class Failed(val message: String) : AuditExportStatus
}

private val AUDIT_CSV_TIMESTAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())

private fun auditCsvTimestamp(): String = AUDIT_CSV_TIMESTAMP.format(Date())

/**
 * Export + share for the execution audit trail: SAF create-document (the user
 * picks where the CSV lands), a cache copy exposed through the manifest
 * FileProvider (guaranteed readable by email apps), and a Share button on
 * success — the same flow as the policy-decision history screen.
 */
@Composable
private fun AuditExportControls() {
    var status by remember { mutableStateOf<AuditExportStatus>(AuditExportStatus.Idle) }
    var shareUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // user cancelled the picker
        val entries = ExecutionAuditHolder.all().sortedByDescending { it.startedMs }
        val bytes = ExecutionCsv.csv(entries).toByteArray(Charsets.UTF_8)
        try {
            context.contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                ?: throw IllegalStateException("Could not open the selected file")
            status = AuditExportStatus.Done
        } catch (e: Exception) {
            status = AuditExportStatus.Failed(e.message ?: e.javaClass.simpleName)
            return@rememberLauncherForActivityResult
        }
        // Share copy in app cache — best effort: Share appears only if this works.
        shareUri = runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "audit-${auditCsvTimestamp()}.csv")
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    status = AuditExportStatus.Idle
                    exportLauncher.launch("audit-${auditCsvTimestamp()}.csv")
                },
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSec),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export CSV", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
            if (status is AuditExportStatus.Done && shareUri != null) {
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = {
                        val uri = shareUri
                        if (uri != null) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_SUBJECT, "Aegis execution audit CSV")
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = ClipData.newRawUri("Aegis execution audit CSV", uri)
                            }
                            context.startActivity(Intent.createChooser(send, "Share execution audit CSV"))
                        }
                    },
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSec),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        when (val s = status) {
            is AuditExportStatus.Idle -> Unit
            is AuditExportStatus.Done -> {
                Spacer(Modifier.height(4.dp))
                Text("Exported — email it with Share", fontSize = 11.sp, color = ReadyCol)
            }
            is AuditExportStatus.Failed -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    s.message,
                    fontSize = 11.sp,
                    color = ErrorCol,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RunCard(run: ExecutionAuditEntry) {
    var expanded by remember { mutableStateOf(false) }
    val outcomeColor = if (run.outcome == RunOutcome.COMPLETED) ReadyCol else ErrorCol
    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(outcomeColor))
                Spacer(Modifier.width(8.dp))
                Text(
                    run.goalDescription,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPri,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(runTime(run.startedMs), fontSize = 11.sp, color = TextTer)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(run.outcome.name.lowercase().replaceFirstChar { it.uppercase() })
                    append(" · ")
                    append(run.tasks.size)
                    append(" task")
                    if (run.tasks.size != 1) append("s")
                    run.durationMs?.let { append(" · ").append(it).append(" ms") }
                },
                fontSize = 11.sp,
                color = TextTer
            )
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                run.tasks.forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (task.status) {
                                com.newax.aegis.engine.intelligence.TaskStatus.COMPLETED -> "✓"
                                com.newax.aegis.engine.intelligence.TaskStatus.FAILED -> "✗"
                                else -> "·"
                            },
                            fontSize = 11.sp,
                            color = if (task.status == com.newax.aegis.engine.intelligence.TaskStatus.FAILED) ErrorCol else TextSec,
                            modifier = Modifier.width(14.dp)
                        )
                        Text(task.description, fontSize = 12.sp, color = TextSec, modifier = Modifier.weight(1f))
                        Text(task.tier ?: "skill", fontSize = 10.sp, color = TextTer, fontFamily = FontFamily.Monospace)
                    }
                    task.result?.let { result ->
                        Spacer(Modifier.height(2.dp))
                        Text(result, fontSize = 11.sp, color = TextTer, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}

private fun runTime(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun PolicyTag() {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(WarnCol.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            "policy",
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = WarnCol,
            letterSpacing = 0.4.sp
        )
    }
}

/**
 * The policy action class a blocked task's skill maps to (same mapping the
 * executor's policy gate uses), or null when the skill has no policy action —
 * the caller then falls back to scrolling to the Policy modes section top.
 */
private fun policyActionClassFor(skillId: String?): String? =
    skillId?.let { SkillRegistry.policyActionFor(it) }?.let { it::class.simpleName }

@Composable
private fun ActionButton(label: String, color: Color, onClick: () -> Unit) {
    TextButton(
        onClick  = onClick,
        colors   = ButtonDefaults.textButtonColors(contentColor = color),
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
