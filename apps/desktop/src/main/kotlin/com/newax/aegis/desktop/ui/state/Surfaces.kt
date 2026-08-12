package com.newax.aegis.desktop.ui.state

import com.newax.aegis.desktop.DesktopCapabilitiesHolder
import com.newax.aegis.desktop.execution.DesktopGoalExecutor
import com.newax.aegis.desktop.planner.DesktopGoalPlanner
import com.newax.aegis.desktop.planner.DesktopPlan
import com.newax.aegis.desktop.planner.Goal
import com.newax.aegis.desktop.planner.GoalState
import com.newax.aegis.desktop.planner.TaskGraph
import com.newax.aegis.platform.PlatformCapabilityRegistry
import com.newax.aegis.platform.windows.WindowsAppIndex

/**
 * The planner surface the Goals board state holder needs — the injectable seam
 * between the UI state and the process-wide [DesktopGoalPlanner] object. The
 * board's decision logic is plain Kotlin and fully testable against a fake;
 * [LivePlannerSurface] is the production adapter.
 */
interface DesktopPlannerSurface {
    fun allGoals(): List<Goal>
    fun getState(goalId: String): GoalState?
    fun getGraph(goalId: String): TaskGraph?
    fun planOf(goalId: String): DesktopPlan?
    fun plan(description: String, registry: PlatformCapabilityRegistry?): DesktopPlan
    fun abandon(goalId: String): Boolean
}

/** Live [DesktopPlannerSurface] — delegates to the process-wide planner object. */
object LivePlannerSurface : DesktopPlannerSurface {

    override fun allGoals(): List<Goal> = DesktopGoalPlanner.allGoals()

    override fun getState(goalId: String): GoalState? = DesktopGoalPlanner.getState(goalId)

    override fun getGraph(goalId: String): TaskGraph? = DesktopGoalPlanner.getGraph(goalId)

    override fun planOf(goalId: String): DesktopPlan? = DesktopGoalPlanner.planOf(goalId)

    override fun plan(description: String, registry: PlatformCapabilityRegistry?): DesktopPlan =
        DesktopGoalPlanner.plan(description, registry)

    override fun abandon(goalId: String): Boolean = DesktopGoalPlanner.abandon(goalId)
}

/**
 * Runs one goal through the real executor — the seam the Goals board's Run
 * action goes through. Injectable so the board state is testable without
 * touching process state.
 */
fun interface GoalRunner {
    suspend fun run(goalId: String, onProgress: (String) -> Unit): Result<Unit>
}

/**
 * Live [GoalRunner] — delegates to [DesktopGoalExecutor] with the process-wide
 * capability registry and the Start Menu app index (null on non-Windows, where
 * the executor's find_app falls back to the stripped target name).
 */
class LiveGoalRunner(
    private val registry: () -> PlatformCapabilityRegistry? = { DesktopCapabilitiesHolder.registry() },
    private val appIndex: () -> WindowsAppIndex? = { null },
) : GoalRunner {

    override suspend fun run(goalId: String, onProgress: (String) -> Unit): Result<Unit> =
        DesktopGoalExecutor.run(
            goalId = goalId,
            registry = registry(),
            appIndex = appIndex(),
            onProgress = onProgress,
        )
}
