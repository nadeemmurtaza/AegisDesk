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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.newax.aegis.engine.bus.NewaxEvent
import com.newax.aegis.engine.bus.NewaxEventBus
import com.newax.aegis.engine.execution.GoalExecutor
import com.newax.aegis.engine.intelligence.Goal
import com.newax.aegis.engine.intelligence.GoalPlanner
import com.newax.aegis.engine.intelligence.PlanResult
import com.newax.aegis.engine.intelligence.SkillRegistry
import com.newax.aegis.engine.intelligence.TaskFailureKind
import com.newax.aegis.engine.intelligence.TaskGraph
import com.newax.aegis.engine.intelligence.TaskStatus
import com.newax.aegis.engine.state.GoalState
import com.newax.aegis.ui.components.EmptyState
import com.newax.aegis.ui.components.InfoTag
import com.newax.aegis.ui.components.StatusChip
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.newax.aegis.ui.theme.NewaxTheme

private data class GoalRow(
    val goal: Goal,
    val state: GoalState?,
    val plan: PlanResult?,
    val graph: TaskGraph?,
)

private fun GoalState.labelRes(): Int = when (this) {
    GoalState.OPEN       -> R.string.goals_state_open
    GoalState.ACTIVE     -> R.string.goals_state_active
    GoalState.BLOCKED    -> R.string.goals_state_blocked
    GoalState.COMPLETED  -> R.string.goals_state_completed
    GoalState.ABANDONED  -> R.string.goals_state_abandoned
}

private fun GoalState.dotColor(): Color = when (this) {
    GoalState.OPEN       -> NewaxTheme.colors.textTertiary
    GoalState.ACTIVE     -> NewaxTheme.colors.success
    GoalState.BLOCKED    -> NewaxTheme.colors.error
    GoalState.COMPLETED  -> NewaxTheme.colors.success
    GoalState.ABANDONED  -> NewaxTheme.colors.textTertiary
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
        NewaxEventBus.flow
            .filter { it is NewaxEvent.TaskUpdated || it is NewaxEvent.GoalBlocked || it is NewaxEvent.GoalCompleted }
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
                colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.goals_header),
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 15.sp,
                        color      = NewaxTheme.colors.textPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value       = draft,
                        onValueChange = { draft = it },
                        placeholder  = { Text(stringResource(R.string.goals_placeholder), fontSize = 14.sp, color = NewaxTheme.colors.textTertiary) },
                        singleLine   = true,
                        shape        = RoundedCornerShape(12.dp),
                        colors       = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NewaxTheme.colors.textSecondary,
                            unfocusedBorderColor = NewaxTheme.colors.border,
                            focusedContainerColor = NewaxTheme.colors.surface,
                            unfocusedContainerColor = NewaxTheme.colors.surface
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
                                    contentDescription = stringResource(R.string.cd_plan_goal),
                                    tint = if (draft.isNotBlank()) NewaxTheme.colors.textPrimary else NewaxTheme.colors.textTertiary
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.goals_decompose_hint),
                        fontSize = 12.sp,
                        color    = NewaxTheme.colors.textTertiary,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        // ── Header / summary ───────────────────────────────────────────────
        if (rows.isNotEmpty()) item {
            Card(
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            pluralStringResource(R.plurals.goals_count, rows.size, rows.size),
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 15.sp,
                            color      = NewaxTheme.colors.textPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        val blocked = rows.count { it.plan != null && !it.plan.feasible }
                        Text(
                            if (blocked == 0) stringResource(R.string.goals_all_feasible)
                            else stringResource(R.string.goals_blocked_count, blocked),
                            fontSize = 13.sp,
                            color    = if (blocked == 0) NewaxTheme.colors.textSecondary else NewaxTheme.colors.warning
                        )
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_goals),
                            tint = NewaxTheme.colors.textSecondary
                        )
                    }
                }
            }
        }

        // ── States ─────────────────────────────────────────────────────────
        if (rows.isEmpty()) item {
            // T3.4: the shared empty surface.
            EmptyState(
                title   = stringResource(R.string.goals_empty),
                message = stringResource(R.string.goals_empty_hint),
                icon    = Icons.Rounded.CheckCircle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp)
            )
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
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, if (blocked) NewaxTheme.colors.warning.copy(alpha = 0.5f) else NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // ── Headline: dot · description · state chip ──────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(row.state?.dotColor() ?: NewaxTheme.colors.textTertiary)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    row.goal.description,
                    fontWeight = FontWeight.Medium,
                    fontSize   = 15.sp,
                    color      = NewaxTheme.colors.textPrimary,
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
                Tag(row.goal.intent, NewaxTheme.colors.textSecondary)
                Tag(stringResource(R.string.goals_priority, row.goal.priority), NewaxTheme.colors.textSecondary)
                if (tasks.isNotEmpty()) Tag(stringResource(R.string.goals_tasks_done, done, tasks.size), if (done == tasks.size) NewaxTheme.colors.success else NewaxTheme.colors.textSecondary)
            }

            // ── Feasibility / block reasons ────────────────────────────────
            when {
                plan == null -> {
                    Spacer(Modifier.height(10.dp))
                    BlockBanner(
                        iconColor = NewaxTheme.colors.textTertiary,
                        title     = stringResource(R.string.goals_no_plan_title),
                        body      = stringResource(R.string.goals_no_plan_body)
                    )
                }
                plan != null && !plan.feasible -> {
                    Spacer(Modifier.height(10.dp))
                    BlockBanner(
                        iconColor = NewaxTheme.colors.warning,
                        title     = stringResource(R.string.goals_blocked_title),
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
                                            .background(NewaxTheme.colors.warning)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        cap,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize   = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color      = NewaxTheme.colors.textPrimary
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.goals_enable_capability),
                                fontSize = 12.sp,
                                color    = NewaxTheme.colors.textTertiary
                            )
                        }
                    }
                    if (plan.missingSkills.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.goals_missing_skills), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textSecondary)
                            plan.missingSkills.forEach { skill ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(NewaxTheme.colors.error)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(skill, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = NewaxTheme.colors.textPrimary)
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
                                    color      = NewaxTheme.colors.textTertiary,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
                else -> {
                    Spacer(Modifier.height(10.dp))
                    BlockBanner(
                        iconColor = NewaxTheme.colors.success,
                        title     = stringResource(R.string.goals_plan_ready_title, tasks.size),
                        body      = stringResource(R.string.goals_plan_ready_body)
                    )
                }
            }

            // ── Live task state: running task + failed reasons ─────────────
            tasks.firstOrNull { it.status == TaskStatus.RUNNING }?.let { runningTask ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = NewaxTheme.colors.warning)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.goals_running, runningTask.description), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
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
                            .background(NewaxTheme.colors.warning)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(failed.description, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textPrimary)
                            Spacer(Modifier.width(8.dp))
                            PolicyTag()
                        }
                        failed.result?.let { result ->
                            Spacer(Modifier.height(2.dp))
                            Text(result, fontSize = 12.sp, color = NewaxTheme.colors.textTertiary, lineHeight = 16.sp)
                        }
                        Spacer(Modifier.height(2.dp))
                        ActionButton(stringResource(R.string.goals_policy_modes), NewaxTheme.colors.warning) {
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
                            .background(NewaxTheme.colors.error)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(failed.description, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textPrimary)
                        failed.result?.let { result ->
                            Spacer(Modifier.height(2.dp))
                            Text(result, fontSize = 12.sp, color = NewaxTheme.colors.textTertiary, lineHeight = 16.sp)
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
                        ActionButton(
                            if (state == GoalState.BLOCKED) stringResource(R.string.goals_retry) else stringResource(R.string.goals_activate),
                            NewaxTheme.colors.success
                        ) {
                            scope.launch {
                                GoalExecutor.run(row.goal.id, context)
                                onChanged()
                            }
                        }
                    }
                    ActionButton(stringResource(R.string.goals_abandon), NewaxTheme.colors.textTertiary) {
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
    // T3.4: the shared status pill — word + colour, announced to screen
    // readers so the colour is never the only signal (SC 1.4.1).
    StatusChip(
        label = stringResource(state?.labelRes() ?: R.string.goals_state_unknown),
        color = state?.dotColor() ?: NewaxTheme.colors.textTertiary
    )
}

@Composable
private fun Tag(text: String, color: Color) {
    // T3.4: the shared neutral tag.
    InfoTag(text = text, color = color)
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
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textPrimary)
            if (body != null) {
                Spacer(Modifier.height(2.dp))
                Text(body, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 17.sp)
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
                stringResource(R.string.goals_recent_runs),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = NewaxTheme.colors.textPrimary,
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
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textSecondary),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_export_csv), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
            if (status is AuditExportStatus.Done && shareUri != null) {
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = {
                        val uri = shareUri
                        if (uri != null) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.goals_share_subject))
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = ClipData.newRawUri(context.getString(R.string.goals_share_subject), uri)
                            }
                            context.startActivity(Intent.createChooser(send, context.getString(R.string.goals_share_chooser)))
                        }
                    },
                    border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textSecondary),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_share), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        when (val s = status) {
            is AuditExportStatus.Idle -> Unit
            is AuditExportStatus.Done -> {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.goals_exported), fontSize = 11.sp, color = NewaxTheme.colors.success)
            }
            is AuditExportStatus.Failed -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    s.message,
                    fontSize = 11.sp,
                    color = NewaxTheme.colors.error,
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
    val outcomeColor = if (run.outcome == RunOutcome.COMPLETED) NewaxTheme.colors.success else NewaxTheme.colors.error
    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
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
                    color = NewaxTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(runTime(run.startedMs), fontSize = 11.sp, color = NewaxTheme.colors.textTertiary)
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
                color = NewaxTheme.colors.textTertiary
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
                            color = if (task.status == com.newax.aegis.engine.intelligence.TaskStatus.FAILED) NewaxTheme.colors.error else NewaxTheme.colors.textSecondary,
                            modifier = Modifier.width(14.dp)
                        )
                        Text(task.description, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                        Text(task.tier ?: "skill", fontSize = 10.sp, color = NewaxTheme.colors.textTertiary, fontFamily = FontFamily.Monospace)
                    }
                    task.result?.let { result ->
                        Spacer(Modifier.height(2.dp))
                        Text(result, fontSize = 11.sp, color = NewaxTheme.colors.textTertiary, lineHeight = 15.sp)
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
            .background(NewaxTheme.colors.warning.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            stringResource(R.string.goals_policy_tag),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = NewaxTheme.colors.warning,
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
