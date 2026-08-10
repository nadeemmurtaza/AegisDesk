package com.newax.aegis.desktop.ui.state

import com.newax.aegis.desktop.planner.DesktopPlan
import com.newax.aegis.desktop.planner.Goal
import com.newax.aegis.desktop.planner.GoalState
import com.newax.aegis.desktop.planner.TaskGraph
import com.newax.aegis.desktop.planner.TaskNode
import com.newax.aegis.platform.PlatformCapabilityRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase B1 — the Goals board state holder. All decision logic (snapshotting,
 * feasibility, run/abandon routing, live progress) is plain Kotlin against
 * injectable planner/runner seams, so the board is verified without touching
 * the process-wide planner or executor.
 */
class GoalsScreenStateTest {

    private val planner = FakePlannerSurface()
    private val runner = RecordingGoalRunner()

    @Test
    fun `board starts in loading and refresh produces an empty board`() = runTest {
        val state = newState(this)

        assertTrue(state.model.value is GoalsUiModel.Loading)
        state.refresh()
        val content = state.model.value as GoalsUiModel.Content
        assertTrue(content.goals.isEmpty())
    }

    @Test
    fun `plan adds a goal to the board with its feasibility`() = runTest {
        val state = newState(this)
        state.refresh()

        state.plan("open spotify")
        val row = (state.model.value as GoalsUiModel.Content).goals.single()

        assertEquals("open spotify", row.goal.description)
        assertTrue(row.feasible)
        assertTrue(row.canRun)
        assertTrue(row.canAbandon)
        assertEquals(0f, row.progress, 0.001f)
    }

    @Test
    fun `infeasible plan surfaces the blocked state`() = runTest {
        planner.setFeasible(false)
        val state = newState(this)
        state.refresh()

        state.plan("open spotify")
        val row = (state.model.value as GoalsUiModel.Content).goals.single()

        assertFalse(row.feasible)
        assertTrue("blocked goal keeps the retry path", row.canRun)
    }

    @Test
    fun `run executes through the runner with live progress lines`() = runTest {
        val state = newState(this)
        state.refresh()
        state.plan("open spotify")
        val goalId = (state.model.value as GoalsUiModel.Content).goals.single().goal.id

        state.run(goalId)
        assertEquals(goalId, state.runningGoalId.value)
        assertTrue(state.runProgress.value.isEmpty())

        advanceUntilIdle()

        assertNull(state.runningGoalId.value)
        assertEquals(listOf(goalId), runner.runs)
        assertEquals("line 1", state.runProgress.value.single().text)
    }

    @Test
    fun `run failure appends the reason to the log`() = runTest {
        val failing = RecordingGoalRunner(Result.failure(IllegalStateException("boom")))
        val state = GoalsScreenState(this, planner, failing) { null }
        state.refresh()
        state.plan("open spotify")
        val goalId = (state.model.value as GoalsUiModel.Content).goals.single().goal.id

        state.run(goalId)
        advanceUntilIdle()

        assertNull(state.runningGoalId.value)
        assertTrue(state.runProgress.value.last().text.contains("boom"))
    }
    @Test
    fun `run while another goal is running is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var invocations = 0
        val blocking = GoalRunner { _, _ ->
            invocations++
            gate.await()
            Result.success(Unit)
        }
        val state = GoalsScreenState(this, planner, blocking) { null }
        state.refresh()
        state.plan("open spotify")
        val goalId = (state.model.value as GoalsUiModel.Content).goals.single().goal.id

        state.run(goalId)
        advanceUntilIdle() // the fake suspends on the gate

        assertEquals(1, invocations)
        assertEquals(goalId, state.runningGoalId.value)
        state.run("some-other-goal")
        assertEquals(1, invocations)

        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(state.runningGoalId.value)
    }

    @Test
    fun `abandon moves the goal to its terminal state`() = runTest {
        val state = newState(this)
        state.refresh()
        state.plan("open spotify")
        val goalId = (state.model.value as GoalsUiModel.Content).goals.single().goal.id

        state.abandon(goalId)
        val row = (state.model.value as GoalsUiModel.Content).goals.single()

        assertEquals(GoalState.ABANDONED, row.state)
        assertFalse(row.canRun)
        assertFalse(row.canAbandon)
    }

    @Test
    fun `planner failures surface as the board error state`() = runTest {
        planner.failAllGoals = true
        val state = newState(this)

        state.refresh()

        assertTrue(state.model.value is GoalsUiModel.Error)
    }

    @Test
    fun `blank plan input is ignored`() = runTest {
        val state = newState(this)
        state.refresh()

        state.plan("   ")
        assertTrue((state.model.value as GoalsUiModel.Content).goals.isEmpty())
    }

    private fun newState(scope: CoroutineScope): GoalsScreenState =
        GoalsScreenState(scope, planner, runner) { null }

    /** In-memory planner with the same lifecycle shapes as the real one. */
    private class FakePlannerSurface : DesktopPlannerSurface {
        private val goals = mutableMapOf<String, Goal>()
        private val states = mutableMapOf<String, GoalState>()
        private val graphs = mutableMapOf<String, TaskGraph>()
        private val plans = mutableMapOf<String, DesktopPlan>()
        private var feasible = true

        var failAllGoals = false

        fun setFeasible(value: Boolean) {
            feasible = value
        }

        override fun allGoals(): List<Goal> {
            if (failAllGoals) throw IllegalStateException("boom")
            return goals.values.toList()
        }

        override fun getState(goalId: String): GoalState? = states[goalId]

        override fun getGraph(goalId: String): TaskGraph? = graphs[goalId]

        override fun planOf(goalId: String): DesktopPlan? = plans[goalId]

        override fun abandon(goalId: String): Boolean {
            val current = states[goalId] ?: return false
            if (current == GoalState.COMPLETED || current == GoalState.ABANDONED) return false
            states[goalId] = GoalState.ABANDONED
            return true
        }

        override fun plan(description: String, registry: PlatformCapabilityRegistry?): DesktopPlan {
            val goal = Goal(description = description, intent = "test")
            goals[goal.id] = goal
            states[goal.id] = GoalState.OPEN
            val task = TaskNode(goalId = goal.id, description = "test task", skillId = "find_app")
            graphs[goal.id] = TaskGraph(goal.id, listOf(task))
            val plan = DesktopPlan(goal, listOf(task), feasible, emptyList(), emptyList(), emptyList())
            plans[goal.id] = plan
            return plan
        }
    }

    /** Records run invocations and emits one progress line per run. */
    private class RecordingGoalRunner(
        private val outcome: Result<Unit> = Result.success(Unit),
    ) : GoalRunner {
        val runs = mutableListOf<String>()

        override suspend fun run(goalId: String, onProgress: (String) -> Unit): Result<Unit> {
            runs.add(goalId)
            onProgress("line 1")
            return outcome
        }
    }
}
