package com.newax.aegis.desktop.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5g — desktop goal lifecycle, mirroring Android's GoalPlanner +
 * StateMachines.goal():
 *  - the goal state machine accepts exactly the Android transition table,
 *  - plan() stores goals/graphs/state machines/plans,
 *  - activate/abandon/block drive the machine,
 *  - updateTask advances tasks (timestamps, progress) and auto-completes,
 *  - a FAILED task blocks the goal (Android's GoalExecutor sequence).
 *
 * Pure JVM and OS-independent: no platform capability is required to exercise
 * the state machine (plans are created with a null registry where feasibility
 * is irrelevant to lifecycle assertions).
 */
class GoalStateMachineTest {

    // ── State machine transitions (Android StateMachines.goal parity) ──────

    @Test
    fun `goal machine allows the legal lifecycle only`() {
        val sm = StateMachines.goal()
        assertEquals(GoalState.OPEN, sm.current)
        assertTrue(sm.transition(GoalState.ACTIVE))
        assertTrue(sm.transition(GoalState.BLOCKED))
        assertTrue(sm.transition(GoalState.ACTIVE)) // BLOCKED reactivates once resolved
        assertTrue(sm.transition(GoalState.COMPLETED))
        assertEquals(GoalState.COMPLETED, sm.current)
        assertFalse(sm.transition(GoalState.ACTIVE))  // COMPLETED is terminal
        assertFalse(sm.transition(GoalState.ABANDONED))
    }

    @Test
    fun `open goal cannot jump to completed or blocked`() {
        val sm = StateMachines.goal()
        assertFalse(sm.transition(GoalState.COMPLETED))
        assertFalse(sm.transition(GoalState.BLOCKED))
        assertEquals(setOf(GoalState.ACTIVE, GoalState.ABANDONED), sm.allowedTransitions())
    }

    @Test
    fun `abandon is terminal from open and active`() {
        val sm = StateMachines.goal()
        assertTrue(sm.transition(GoalState.ABANDONED))
        assertFalse(sm.transition(GoalState.ACTIVE))

        val active = StateMachines.goal()
        assertTrue(active.transition(GoalState.ACTIVE))
        assertTrue(active.transition(GoalState.ABANDONED))
        assertFalse(active.transition(GoalState.BLOCKED))
    }

    // ── Planner-level goal lifecycle ───────────────────────────────────────

    @Test
    fun `plan stores the goal in open state`() {
        val plan = DesktopGoalPlanner.plan("open spotify", null)

        assertEquals(plan.goal, DesktopGoalPlanner.getGoal(plan.goal.id))
        assertEquals(GoalState.OPEN, DesktopGoalPlanner.getState(plan.goal.id))
        assertEquals(plan, DesktopGoalPlanner.planOf(plan.goal.id))
        assertTrue(DesktopGoalPlanner.allGoals().any { it.id == plan.goal.id })
    }

    @Test
    fun `activate advances the goal and lists it as active`() {
        val plan = DesktopGoalPlanner.plan("open spotify", null)

        assertTrue(DesktopGoalPlanner.activate(plan.goal.id))
        assertEquals(GoalState.ACTIVE, DesktopGoalPlanner.getState(plan.goal.id))
        assertEquals(listOf(plan.goal.id), DesktopGoalPlanner.activeGoals().map { it.id })
        assertFalse(DesktopGoalPlanner.activate(plan.goal.id)) // already ACTIVE
    }

    @Test
    fun `abandon terminates the goal`() {
        val plan = DesktopGoalPlanner.plan("open spotify", null)

        assertTrue(DesktopGoalPlanner.abandon(plan.goal.id))
        assertEquals(GoalState.ABANDONED, DesktopGoalPlanner.getState(plan.goal.id))
        assertFalse(DesktopGoalPlanner.activate(plan.goal.id)) // terminal
    }

    // ── Task state machine + progress ──────────────────────────────────────

    @Test
    fun `task progress advances with task status`() {
        val plan = DesktopGoalPlanner.plan("open spotify", null)
        val graph = DesktopGoalPlanner.getGraph(plan.goal.id)!!

        assertEquals(2, graph.tasks.size)
        assertEquals(0f, graph.progress())

        DesktopGoalPlanner.updateTask(plan.goal.id, graph.tasks[0].id, TaskStatus.RUNNING)
        assertNotNull(graph.tasks[0].startedMs)

        DesktopGoalPlanner.updateTask(plan.goal.id, graph.tasks[0].id, TaskStatus.COMPLETED)
        assertEquals(0.5f, graph.progress(), 0.001f)
        assertNotNull(graph.tasks[0].completedMs)
        assertFalse(graph.hasFailed())
        assertFalse(graph.isComplete())
    }

    @Test
    fun `completing every task auto-completes the goal`() {
        val plan = DesktopGoalPlanner.plan("open spotify", null)
        val graph = DesktopGoalPlanner.getGraph(plan.goal.id)!!
        DesktopGoalPlanner.activate(plan.goal.id)

        graph.tasks.forEach { task ->
            DesktopGoalPlanner.updateTask(plan.goal.id, task.id, TaskStatus.COMPLETED, "ok")
        }

        assertTrue(graph.isComplete())
        assertEquals(1f, graph.progress(), 0.001f)
        assertEquals(GoalState.COMPLETED, DesktopGoalPlanner.getState(plan.goal.id))
    }

    @Test
    fun `failed task blocks the goal and can reactivate`() {
        val plan = DesktopGoalPlanner.plan("open spotify", null)
        val graph = DesktopGoalPlanner.getGraph(plan.goal.id)!!
        DesktopGoalPlanner.activate(plan.goal.id)

        DesktopGoalPlanner.updateTask(plan.goal.id, graph.tasks[0].id, TaskStatus.FAILED, "no such app")
        assertTrue(graph.hasFailed())

        // Android's GoalExecutor sequence: task failure → block(goalId).
        assertTrue(DesktopGoalPlanner.block(plan.goal.id))
        assertEquals(GoalState.BLOCKED, DesktopGoalPlanner.getState(plan.goal.id))

        // BLOCKED → ACTIVE once the blocker is resolved.
        assertTrue(DesktopGoalPlanner.activate(plan.goal.id))
        assertEquals(GoalState.ACTIVE, DesktopGoalPlanner.getState(plan.goal.id))
    }

    @Test
    fun `skipped tasks count toward completion`() {
        val plan = DesktopGoalPlanner.plan("send a message", null)
        val graph = DesktopGoalPlanner.getGraph(plan.goal.id)!!
        DesktopGoalPlanner.activate(plan.goal.id)

        DesktopGoalPlanner.updateTask(plan.goal.id, graph.tasks[0].id, TaskStatus.SKIPPED)
        DesktopGoalPlanner.updateTask(plan.goal.id, graph.tasks[1].id, TaskStatus.COMPLETED)

        assertTrue(graph.isComplete())
        assertEquals(GoalState.COMPLETED, DesktopGoalPlanner.getState(plan.goal.id))
    }

    @Test
    fun `topological order respects dependencies`() {
        val plan = DesktopGoalPlanner.plan("send a message", null)
        val graph = DesktopGoalPlanner.getGraph(plan.goal.id)!!

        assertEquals(listOf("find_contact", "send_message"), graph.topologicalOrder().map { it.skillId })
        assertTrue(graph.tasks[1].dependencies.contains(graph.tasks[0].id))
        assertTrue(graph.tasks[0].dependencies.isEmpty())
    }
}
