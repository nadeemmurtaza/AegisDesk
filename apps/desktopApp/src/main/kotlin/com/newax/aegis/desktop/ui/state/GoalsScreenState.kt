package com.newax.aegis.desktop.ui.state

import com.newax.aegis.desktop.planner.DesktopPlan
import com.newax.aegis.desktop.planner.Goal
import com.newax.aegis.desktop.planner.GoalState
import com.newax.aegis.desktop.planner.TaskStatus
import com.newax.aegis.platform.PlatformCapabilityRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One task line on a goal card — status plus the executor's result text. */
data class GoalTaskUi(
    val description: String,
    val status: TaskStatus,
    val result: String?,
)

/** One goal on the board, pre-computed for rendering (progress, feasibility, actions). */
data class GoalUiRow(
    val goal: Goal,
    val state: GoalState,
    val plan: DesktopPlan?,
    val tasks: List<GoalTaskUi>,
    val progress: Float,
    val running: Boolean,
) {
    /** True when every task skill exists and every required capability is ready. */
    val feasible: Boolean get() = plan?.feasible == true

    /** A goal the user can start or retry — OPEN, or BLOCKED (re-checks live on run). */
    val canRun: Boolean get() = state == GoalState.OPEN || state == GoalState.BLOCKED

    /** Abandon is available until the goal reaches a terminal state. */
    val canAbandon: Boolean get() = state != GoalState.COMPLETED && state != GoalState.ABANDONED
}

/** The board model: loading (before the first snapshot), error, or content. */
sealed interface GoalsUiModel {
    data object Loading : GoalsUiModel
    data class Error(val message: String) : GoalsUiModel
    data class Content(val goals: List<GoalUiRow>) : GoalsUiModel
}

/** One live line of the running goal's executor output (goalId so the UI can scope it). */
data class RunProgressLine(val goalId: String, val text: String)

/**
 * Goals board state — the desktop counterpart of Android's GoalsScreen view
 * model (the `printGoals` / `printRunGoal` CLI logic lifted into a state
 * holder). All decision logic lives here in plain Kotlin; the Compose screen
 * only renders.
 *
 * The planner and the goal runner are injectable seams
 * ([DesktopPlannerSurface] / [GoalRunner]) so the board is fully testable with
 * fakes; the live defaults drive the process-wide planner and executor. Runs
 * are serialized: one goal executes at a time, and a run while a goal is
 * running is ignored (the UI disables Run; the guard is the safety net).
 */
class GoalsScreenState(
    private val scope: CoroutineScope,
    private val planner: DesktopPlannerSurface = LivePlannerSurface,
    private val runner: GoalRunner,
    private val registry: () -> PlatformCapabilityRegistry?,
) {

    private val _model = MutableStateFlow<GoalsUiModel>(GoalsUiModel.Loading)
    val model: StateFlow<GoalsUiModel> = _model.asStateFlow()

    private val _runningGoalId = MutableStateFlow<String?>(null)
    val runningGoalId: StateFlow<String?> = _runningGoalId.asStateFlow()

    private val _runProgress = MutableStateFlow<List<RunProgressLine>>(emptyList())
    val runProgress: StateFlow<List<RunProgressLine>> = _runProgress.asStateFlow()

    private val MAX_PROGRESS_LINES = 200

    /** Re-snapshots the planner into the board model (Loading → Content/Error). */
    fun refresh() {
        try {
            val rows = planner.allGoals()
                .sortedByDescending { it.priority }
                .map { goal -> rowOf(goal) }
            _model.value = GoalsUiModel.Content(rows)
        } catch (e: Exception) {
            _model.value = GoalsUiModel.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Plans a new goal through the planner and refreshes the board. Blank input is ignored. */
    fun plan(description: String) {
        val trimmed = description.trim()
        if (trimmed.isEmpty()) return
        planner.plan(trimmed, registry())
        refresh()
    }

    /** Gives up on a goal — OPEN/ACTIVE/BLOCKED → ABANDONED (mirrors the board button). */
    fun abandon(goalId: String) {
        planner.abandon(goalId)
        refresh()
    }

    /**
     * Runs a goal's plan through the executor, surfacing progress lines live.
     * Ignored while another goal is running (one execution at a time); on
     * failure the reason is appended to the run log and the board refreshes to
     * the BLOCKED state the executor set.
     */
    fun run(goalId: String) {
        if (_runningGoalId.value != null) return
        _runningGoalId.value = goalId
        _runProgress.value = emptyList()
        scope.launch {
            val result = runner.run(goalId) { line -> appendProgress(goalId, line) }
            _runningGoalId.value = null
            refresh()
            if (result.isFailure) {
                appendProgress(goalId, result.exceptionOrNull()?.message ?: "execution failed")
            }
        }
    }

    private fun appendProgress(goalId: String, line: String) {
        val next = _runProgress.value + RunProgressLine(goalId, line)
        _runProgress.value = next.takeLast(MAX_PROGRESS_LINES)
    }

    private fun rowOf(goal: Goal): GoalUiRow {
        val state = planner.getState(goal.id) ?: GoalState.OPEN
        val graph = planner.getGraph(goal.id)
        val tasks = graph?.tasks.orEmpty().map { GoalTaskUi(it.description, it.status, it.result) }
        val progress = graph?.progress() ?: 0f
        return GoalUiRow(
            goal = goal,
            state = state,
            plan = planner.planOf(goal.id),
            tasks = tasks,
            progress = progress,
            running = tasks.any { it.status == TaskStatus.RUNNING },
        )
    }
}
