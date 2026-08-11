package com.newax.aegis.desktop

import com.newax.aegis.desktop.planner.Goal
import com.newax.aegis.desktop.planner.GoalState
import com.newax.aegis.desktop.planner.TaskGraph
import com.newax.aegis.desktop.planner.TaskNode
import com.newax.aegis.desktop.planner.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * Phase B3 — the JSON goals store. Pure JVM: a full snapshot must round-trip
 * byte-for-byte through the file, and every named failure mode (missing,
 * corrupt, unsupported schema) must be an honest empty start — never a crash.
 */
class GoalsStoreTest {

    private fun snapshot(): GoalsSnapshot {
        val goal = Goal(id = "g1", description = "open spotify", intent = "open", priority = 3, createdMs = 1_000L)
        val task = TaskNode(
            id = "t1",
            goalId = "g1",
            description = "find app",
            skillId = "find_app",
            status = TaskStatus.COMPLETED,
            result = "via EXACT_TARGET · index match 'Spotify'",
            startedMs = 1_100L,
            completedMs = 1_200L,
        )
        return GoalsSnapshot(
            goals = listOf(goal),
            graphs = listOf(TaskGraph(goalId = "g1", tasks = listOf(task), createdMs = 900L)),
            states = mapOf("g1" to GoalState.BLOCKED),
            plans = mapOf(
                "g1" to PlanVerdict(
                    feasible = false,
                    missingSkills = emptyList(),
                    missingCapabilities = listOf("OPEN_APP"),
                    warnings = listOf("Capability 'OPEN_APP' is not ready (no registered capability; candidates: PROCESSES, DESKTOP)"),
                )
            ),
            runs = listOf(
                ExecutionAuditEntry(
                    goalId = "g1",
                    goalDescription = "open spotify",
                    outcome = "BLOCKED",
                    reason = "cap not ready",
                    tiers = listOf("EXACT_TARGET"),
                    taskCount = 2,
                    startedMs = 1_100L,
                    completedMs = 1_200L,
                )
            ),
        )
    }

    @Test
    fun `round-trips a full snapshot through the file`() {
        val file = tempFile()
        val original = snapshot()

        FileGoalsStore(file).save(original)
        val loaded = FileGoalsStore(file).load()

        assertEquals(original, loaded)
    }

    @Test
    fun `empty snapshot round-trips`() {
        val file = tempFile()
        val empty = GoalsSnapshot(emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList())

        FileGoalsStore(file).save(empty)
        assertEquals(empty, FileGoalsStore(file).load())
    }

    @Test
    fun `missing file loads as an honest empty start`() {
        val dir = Files.createTempDirectory("goals-store-test")
        assertNull(FileGoalsStore(dir.resolve("nope.json")).load())
    }

    @Test
    fun `corrupt file loads as an honest empty start`() {
        val file = tempFile()
        Files.write(file, "{\"version\": 1, \"goals\": [not valid json".toByteArray())

        assertNull(FileGoalsStore(file).load())
    }

    @Test
    fun `unsupported schema version is not loaded`() {
        val file = tempFile()
        Files.write(file, "{\"version\": 99, \"goals\": [], \"graphs\": [], \"states\": {}, \"plans\": {}, \"runs\": []}".toByteArray())

        assertNull(FileGoalsStore(file).load())
    }

    @Test
    fun `save is idempotent across instances`() {
        val file = tempFile()
        val first = snapshot()

        FileGoalsStore(file).save(first)
        FileGoalsStore(file).save(first) // second write over the first
        assertEquals(first, FileGoalsStore(file).load())
    }

    private fun tempFile(): java.nio.file.Path {
        val dir = Files.createTempDirectory("goals-store-test")
        return dir.resolve("goals.json")
    }
}
