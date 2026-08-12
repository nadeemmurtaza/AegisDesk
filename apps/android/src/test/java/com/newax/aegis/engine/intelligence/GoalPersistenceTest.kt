package com.newax.aegis.engine.intelligence

import com.newax.aegis.engine.state.GoalState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Track A5 — goal persistence semantics. Pure JVM (no Android classes touched):
 * tests the snapshot/restore contract of GoalPlanner, which is what the org.json
 * codec stores in kv_store and rehydrates at bootstrap.
 */
class GoalPersistenceTest {

    @Before fun setUp() { GoalPlanner.onChange = null }
    @After fun tearDown() { GoalPlanner.onChange = null }

    @Test
    fun roundTrip_preservesGoalGraphPlanAndState() {
        val planned = GoalPlanner.plan("open spotify", priority = 8, tags = listOf("fun"))
        GoalPlanner.activate(planned.goal.id)
        val tasks = GoalPlanner.getGraph(planned.goal.id)!!.tasks
        GoalPlanner.updateTask(planned.goal.id, tasks[0].id, TaskStatus.COMPLETED, "resolved")
        GoalPlanner.updateTask(planned.goal.id, tasks[1].id, TaskStatus.RUNNING, "starting")

        val s = GoalPlanner.snapshot().first { it.goal.id == planned.goal.id }
        assertEquals(GoalState.ACTIVE, s.state)
        assertEquals(planned.goal.description, s.goal.description)
        assertEquals(8, s.goal.priority)
        assertEquals(listOf("fun"), s.goal.tags)
        assertEquals(TaskStatus.COMPLETED, s.graph.tasks[0].status)
        assertEquals("resolved", s.graph.tasks[0].result)
        assertEquals(TaskStatus.RUNNING, s.graph.tasks[1].status)
        assertNotNull(s.plan)
        assertEquals(listOf("fun"), s.plan!!.goal.tags)

        // Restart: wipe nothing, rehydrate — restore is idempotent per goal id.
        GoalPlanner.restore(listOf(s))
        GoalPlanner.restore(listOf(s))

        assertEquals(1, GoalPlanner.snapshot().count { it.goal.id == planned.goal.id })
        val restored = GoalPlanner.getGoal(planned.goal.id)!!
        assertEquals(planned.goal.description, restored.description)
        assertEquals(GoalState.ACTIVE, GoalPlanner.getState(planned.goal.id))
        assertEquals(TaskStatus.COMPLETED, GoalPlanner.getGraph(planned.goal.id)!!.tasks[0].status)
        assertEquals("resolved", GoalPlanner.getGraph(planned.goal.id)!!.tasks[0].result)
        assertNotNull(GoalPlanner.planOf(planned.goal.id))
    }

    @Test
    fun runningTaskRevertsToPendingOnRestore() {
        val planned = GoalPlanner.plan("open spotify")
        GoalPlanner.activate(planned.goal.id)
        val task = GoalPlanner.getGraph(planned.goal.id)!!.tasks[0]
        GoalPlanner.updateTask(planned.goal.id, task.id, TaskStatus.RUNNING, "started")

        GoalPlanner.restore(GoalPlanner.snapshot().filter { it.goal.id == planned.goal.id })

        val restored = GoalPlanner.getGraph(planned.goal.id)!!.tasks[0]
        assertEquals(TaskStatus.PENDING, restored.status)
        assertNull(restored.startedMs)
        assertNull(restored.result)
        // The goal itself stays ACTIVE — a re-run picks the reverted task up.
        assertEquals(GoalState.ACTIVE, GoalPlanner.getState(planned.goal.id))
    }

    @Test
    fun blockedGoalSurvivesRestoreAndCanReactivate() {
        val planned = GoalPlanner.plan("open spotify")
        GoalPlanner.activate(planned.goal.id)
        GoalPlanner.block(planned.goal.id)
        assertEquals(GoalState.BLOCKED, GoalPlanner.getState(planned.goal.id))

        GoalPlanner.restore(GoalPlanner.snapshot().filter { it.goal.id == planned.goal.id })

        assertEquals(GoalState.BLOCKED, GoalPlanner.getState(planned.goal.id))
        // Re-run of a restored BLOCKED goal is legal (BLOCKED → ACTIVE), and the
        // executor re-checks capability/policy gates live rather than trusting the save.
        assertTrue(GoalPlanner.activate(planned.goal.id))
        assertEquals(GoalState.ACTIVE, GoalPlanner.getState(planned.goal.id))
    }

    @Test
    fun completedGoalStaysCompleteOnRestore() {
        val planned = GoalPlanner.plan("open spotify")
        GoalPlanner.activate(planned.goal.id)
        GoalPlanner.getGraph(planned.goal.id)!!.tasks.forEach { task ->
            GoalPlanner.updateTask(planned.goal.id, task.id, TaskStatus.COMPLETED, "done")
        }
        assertEquals(GoalState.COMPLETED, GoalPlanner.getState(planned.goal.id))

        GoalPlanner.restore(GoalPlanner.snapshot().filter { it.goal.id == planned.goal.id })

        assertEquals(GoalState.COMPLETED, GoalPlanner.getState(planned.goal.id))
        assertTrue(GoalPlanner.getGraph(planned.goal.id)!!.isComplete())
    }

    @Test
    fun failureKindSurvivesSnapshotAndRestore() {
        val planned = GoalPlanner.plan("send message")
        GoalPlanner.activate(planned.goal.id)
        val tasks = GoalPlanner.getGraph(planned.goal.id)!!.tasks
        GoalPlanner.updateTask(
            planned.goal.id, tasks[0].id, TaskStatus.FAILED,
            "cannot run autonomously", TaskFailureKind.POLICY
        )
        GoalPlanner.updateTask(
            planned.goal.id, tasks[1].id, TaskStatus.FAILED,
            "capability not ready", TaskFailureKind.CAPABILITY
        )
        // The executor blocks the goal on the first task failure — mirror that here.
        GoalPlanner.block(planned.goal.id)

        GoalPlanner.restore(GoalPlanner.snapshot().filter { it.goal.id == planned.goal.id })

        val restored = GoalPlanner.getGraph(planned.goal.id)!!.tasks
        assertEquals(TaskFailureKind.POLICY, restored[0].failureKind)
        assertEquals("cannot run autonomously", restored[0].result)
        assertEquals(TaskFailureKind.CAPABILITY, restored[1].failureKind)
        assertEquals(GoalState.BLOCKED, GoalPlanner.getState(planned.goal.id))
    }

    @Test
    fun failureKindClearedWhenTaskRunsAgain() {
        val planned = GoalPlanner.plan("send message")
        GoalPlanner.activate(planned.goal.id)
        val task = GoalPlanner.getGraph(planned.goal.id)!!.tasks[0]

        GoalPlanner.updateTask(planned.goal.id, task.id, TaskStatus.FAILED, "blocked", TaskFailureKind.POLICY)
        assertEquals(TaskFailureKind.POLICY, GoalPlanner.getGraph(planned.goal.id)!!.tasks[0].failureKind)

        // Retry: a fresh run must not carry the stale policy chip.
        GoalPlanner.updateTask(planned.goal.id, task.id, TaskStatus.RUNNING)
        assertNull(GoalPlanner.getGraph(planned.goal.id)!!.tasks[0].failureKind)

        GoalPlanner.updateTask(planned.goal.id, task.id, TaskStatus.COMPLETED, "done")
        assertNull(GoalPlanner.getGraph(planned.goal.id)!!.tasks[0].failureKind)

        // And a new failure records its own kind.
        GoalPlanner.updateTask(planned.goal.id, task.id, TaskStatus.FAILED, "cap", TaskFailureKind.CAPABILITY)
        assertEquals(TaskFailureKind.CAPABILITY, GoalPlanner.getGraph(planned.goal.id)!!.tasks[0].failureKind)
    }

    @Test
    fun onChangeFiresAfterEveryMutation() {
        val calls = mutableListOf<Int>()
        GoalPlanner.onChange = { snapshots -> calls += snapshots.count { it.goal.id.isNotBlank() } }
        val planned = GoalPlanner.plan("open spotify")              // 1
        GoalPlanner.activate(planned.goal.id)                       // 2
        val tasks = GoalPlanner.getGraph(planned.goal.id)!!.tasks
        GoalPlanner.updateTask(planned.goal.id, tasks[0].id, TaskStatus.COMPLETED) // 3
        GoalPlanner.block(planned.goal.id)                          // 4
        GoalPlanner.abandon(planned.goal.id)                        // 5
        assertEquals(5, calls.size)
    }

    @Test
    fun restoreDoesNotFireOnChange() {
        val planned = GoalPlanner.plan("open spotify")
        var calls = 0
        GoalPlanner.onChange = { calls++ }
        GoalPlanner.restore(GoalPlanner.snapshot().filter { it.goal.id == planned.goal.id })
        assertEquals(0, calls) // no persistence loop on boot
    }
}
