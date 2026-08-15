package com.newax.aegis.desktop.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.newax.aegis.desktop.ExecutionAuditEntry
import com.newax.aegis.desktop.TaskFailureKind
import com.newax.aegis.desktop.planner.GoalState
import com.newax.aegis.desktop.planner.TaskStatus
import com.newax.aegis.desktop.ui.state.GoalTaskUi
import com.newax.aegis.desktop.ui.state.GoalUiRow
import com.newax.aegis.desktop.ui.state.GoalsScreenState
import com.newax.aegis.desktop.ui.state.GoalsUiModel
import com.newax.aegis.desktop.ui.state.RunProgressLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Goals board — the desktop face of the goal lifecycle (the `printGoals` /
 * `printRunGoal` CLI logic lifted into a window, mirroring Android's
 * GoalsScreen). Plan input, per-goal state chips and progress bars, blocked
 * capability warnings with reasons, live executor output during a run, and
 * Run/Abandon actions. A blocked goal shows exactly why and re-checks its
 * capabilities live when Run is pressed.
 */
@Composable
fun GoalsScreen(
    state: GoalsScreenState,
    /** Jump to the Policy tab — the "Change mode" action on a policy-blocked task. */
    onOpenPolicy: () -> Unit = {},
) {
    val model by state.model.collectAsState()
    val runningGoalId by state.runningGoalId.collectAsState()
    val runProgress by state.runProgress.collectAsState()
    val recentRuns by state.recentRuns.collectAsState()
    var draft by remember { mutableStateOf("") }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // ── New goal input ─────────────────────────────────────────────────
        item {
            NewGoalCard(
                draft = draft,
                onDraftChange = { draft = it },
                onPlan = { text ->
                    state.plan(text)
                    draft = ""
                },
            )
        }

        when (val current = model) {
            is GoalsUiModel.Loading -> item {
                EmptyState("Loading goals…", null, iconColor = WarningColor)
            }
            is GoalsUiModel.Error -> item {
                EmptyState("Could not load goals", current.message, iconColor = ErrorColor)
            }
            is GoalsUiModel.Content -> {
                if (current.goals.isEmpty()) {
                    item {
                        EmptyState(
                            "No goals yet",
                            "Type a goal above to see Newax's plan — and why it might be blocked.",
                        )
                    }
                } else {
                    // ── Summary card ──────────────────────────────────────
                    item {
                        GoalsSummaryCard(
                            total = current.goals.size,
                            blocked = current.goals.count { !it.feasible },
                            onRefresh = { state.refresh() },
                        )
                    }
                    items(current.goals, key = { it.goal.id }) { row ->
                        GoalCard(
                            row = row,
                            isRunning = runningGoalId == row.goal.id,
                            onRun = { state.run(row.goal.id) },
                            onAbandon = { state.abandon(row.goal.id) },
                            onOpenPolicy = onOpenPolicy,
                        )
                    }
                }
            }
        }

        // ── Live run log (surfaced while a goal executes) ──────────────────
        if (runProgress.isNotEmpty()) {
            item { RunLogCard(runningGoalId, runProgress) }
        }

        // ── Execution audit (Phase B3): every run, newest first ─────────────
        if (recentRuns.isNotEmpty()) {
            item { RecentRunsCard(recentRuns) }
        }
    }
}

@Composable
private fun NewGoalCard(
    draft: String,
    onDraftChange: (String) -> Unit,
    onPlan: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("New goal", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimaryColor)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("e.g. open spotify", fontSize = 14.sp, color = TextTertiaryColor) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TextSecondaryColor,
                    unfocusedBorderColor = BorderColor,
                    focusedContainerColor = SurfaceColor,
                    unfocusedContainerColor = SurfaceColor
                ),
                trailingIcon = {
                    IconButton(
                        enabled = draft.isNotBlank(),
                        onClick = { if (draft.isNotBlank()) onPlan(draft) }
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "Plan goal",
                            tint = if (draft.isNotBlank()) TextPrimaryColor else TextTertiaryColor
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Newax decomposes the goal, then checks each skill's capabilities against the platform registry before calling it feasible.",
                fontSize = 12.sp,
                color = TextTertiaryColor,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun GoalsSummaryCard(total: Int, blocked: Int, onRefresh: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "$total ${if (total == 1) "goal" else "goals"}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TextPrimaryColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (blocked == 0) "All plans feasible · nothing blocked"
                    else "$blocked blocked by platform capabilities",
                    fontSize = 13.sp,
                    color = if (blocked == 0) TextSecondaryColor else WarningColor
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh goals", tint = TextSecondaryColor)
            }
        }
    }
}

@Composable
private fun GoalCard(
    row: GoalUiRow,
    isRunning: Boolean,
    onRun: () -> Unit,
    onAbandon: () -> Unit,
    onOpenPolicy: () -> Unit,
) {
    val blocked = !row.feasible
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, if (blocked) WarningColor.copy(alpha = 0.5f) else BorderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // ── Headline: dot · description · state chip ──────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(row.state.dotColor())
                Spacer(Modifier.width(10.dp))
                Text(
                    row.goal.description,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = TextPrimaryColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(row.state.label(), row.state.dotColor())
            }

            // ── Meta: intent · priority · tasks ───────────────────────────
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(row.goal.intent, TextSecondaryColor)
                Tag("Priority ${row.goal.priority}", TextSecondaryColor)
                if (row.tasks.isNotEmpty()) {
                    val done = row.tasks.count { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.SKIPPED }
                    Tag("${done}/${row.tasks.size} tasks done", if (done == row.tasks.size) ReadyColor else TextSecondaryColor)
                }
            }

            // ── Progress bar ──────────────────────────────────────────────
            if (row.tasks.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { row.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (row.state == GoalState.BLOCKED) ErrorColor else ReadyColor,
                    trackColor = SurfaceMutedColor
                )
            }

            // ── Feasibility / block reasons (a blocked goal shows why) ────
            when {
                row.plan == null -> {
                    Spacer(Modifier.height(10.dp))
                    BlockBanner(MutedColor, "No plan yet", "This goal has not been through the planner pre-flight.")
                }
                !row.feasible -> {
                    Spacer(Modifier.height(10.dp))
                    BlockBanner(WarningColor, "Blocked — the platform can't run this yet", null)
                    if (row.plan.missingCapabilities.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.plan.missingCapabilities.forEach { cap ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(WarningColor))
                                    Spacer(Modifier.width(8.dp))
                                    Text(cap, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = TextPrimaryColor)
                                }
                            }
                            Text(
                                "Enable the matching capability on the Status screen, then retry.",
                                fontSize = 12.sp,
                                color = TextTertiaryColor
                            )
                        }
                    }
                    if (row.plan.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            row.plan.warnings.forEach { warning ->
                                Text("· $warning", fontSize = 12.sp, color = TextTertiaryColor, lineHeight = 17.sp)
                            }
                        }
                    }
                    if (row.state == GoalState.BLOCKED) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Run re-checks every capability live once the blocker clears.",
                            fontSize = 12.sp,
                            color = TextTertiaryColor
                        )
                    }
                }
                else -> {
                    Spacer(Modifier.height(10.dp))
                    BlockBanner(
                        ReadyColor,
                        "Plan ready · ${row.tasks.size} tasks",
                        "No platform blockers — every skill resolves through a ready capability."
                    )
                }
            }

            // ── Task list with statuses ───────────────────────────────────
            if (row.tasks.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.tasks.forEach { task -> TaskLine(task, onOpenPolicy) }
                }
            }

            // ── Lifecycle actions ─────────────────────────────────────────
            if (row.canRun || row.canAbandon) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (row.canRun && !isRunning) {
                        Button(
                            onClick = onRun,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (row.state == GoalState.BLOCKED) WarningColor else ReadyColor,
                                contentColor = SurfaceColor
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                if (row.state == GoalState.BLOCKED) "Retry" else "Run",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (isRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = WarningColor)
                        Spacer(Modifier.width(6.dp))
                        Text("Running…", fontSize = 12.sp, color = TextSecondaryColor)
                    }
                    if (row.canAbandon && !isRunning) {
                        TextButton(
                            onClick = onAbandon,
                            colors = ButtonDefaults.textButtonColors(contentColor = TextTertiaryColor),
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        ) {
                            Text("Abandon", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskLine(task: GoalTaskUi, onOpenPolicy: () -> Unit) {
    val color = when (task.status) {
        TaskStatus.COMPLETED -> ReadyColor
        TaskStatus.SKIPPED -> MutedColor
        TaskStatus.FAILED -> ErrorColor
        TaskStatus.RUNNING -> WarningColor
        TaskStatus.PENDING -> TextTertiaryColor
    }
    val policyBlocked = task.status == TaskStatus.FAILED && task.failureKind == TaskFailureKind.POLICY
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (policyBlocked) WarningColor else color))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.description,
                fontSize = 12.sp,
                fontWeight = if (task.status == TaskStatus.RUNNING) FontWeight.Medium else FontWeight.Normal,
                color = TextPrimaryColor
            )
            task.result?.let { result ->
                Spacer(Modifier.height(2.dp))
                Text(
                    result,
                    fontSize = 11.5.sp,
                    color = if (policyBlocked) WarningColor else TextTertiaryColor,
                    lineHeight = 16.sp
                )
            }
        }
        if (policyBlocked) {
            Spacer(Modifier.width(8.dp))
            Tag("policy", WarningColor)
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = onOpenPolicy,
                colors = ButtonDefaults.textButtonColors(contentColor = WarningColor),
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
            ) {
                Text("Change mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Spacer(Modifier.width(8.dp))
            Text(task.status.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 11.sp, color = color)
        }
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
        Box(Modifier.size(10.dp).clip(CircleShape).background(iconColor))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimaryColor)
            if (body != null) {
                Spacer(Modifier.height(2.dp))
                Text(body, fontSize = 12.sp, color = TextSecondaryColor, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun RecentRunsCard(runs: List<ExecutionAuditEntry>) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Recent runs",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = TextPrimaryColor
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Every goal execution, audited with its outcome and launch tier — persisted under ~/.aegis/goals.json.",
                fontSize = 12.sp,
                color = TextTertiaryColor,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                runs.forEach { run ->
                    val ok = run.outcome == "COMPLETED"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) ReadyColor else ErrorColor))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                run.goalDescription,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimaryColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildString {
                                    if (run.tiers.isNotEmpty()) append(run.tiers.joinToString(" · ")).append("  ·  ")
                                    append("${run.taskCount} tasks").append("  ·  ")
                                    append(formatRunTime(run.completedMs))
                                    if (run.durationMs > 0) append("  ·  ${run.durationMs} ms")
                                },
                                fontSize = 11.5.sp,
                                color = TextTertiaryColor,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                            run.reason?.let { reason ->
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    reason,
                                    fontSize = 11.5.sp,
                                    color = ErrorColor,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        StatusChip(run.outcome, if (ok) ReadyColor else ErrorColor)
                    }
                }
            }
        }
    }
}

private val RUN_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun formatRunTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(RUN_TIME_FORMATTER)

@Composable
private fun RunLogCard(runningGoalId: String?, lines: List<RunProgressLine>) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (runningGoalId != null) "Run log · executing" else "Run log · last run",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = TextPrimaryColor,
                    modifier = Modifier.weight(1f)
                )
                if (runningGoalId != null) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = WarningColor)
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                lines.forEach { line ->
                    Text(
                        line.text,
                        fontSize = 12.sp,
                        color = if (line.text.contains("FAILED")) ErrorColor else TextSecondaryColor,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}
