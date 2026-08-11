package com.newax.aegis.engine.audit

import com.newax.aegis.engine.intelligence.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android parity — the execution-audit CSV renderer. Pure JVM: the escaping
 * rules are the correctness-sensitive part and must be RFC-4180-correct
 * (fields with commas/quotes/newlines quoted, quotes doubled), with columns
 * mirroring the desktop `AuditExporter` as far as the Android record supports
 * (goal_id, goal_description, outcome, task_count, started_ms, completed_ms,
 * duration_ms).
 */
class ExecutionCsvTest {

    private fun entry(
        id: String,
        goalId: String = "g1",
        startedMs: Long = 1_000L,
        finishedMs: Long? = 2_000L,
        outcome: RunOutcome = RunOutcome.COMPLETED,
        description: String = "open spotify",
    ) = ExecutionAuditEntry(
        id = id,
        goalId = goalId,
        goalDescription = description,
        outcome = outcome,
        startedMs = startedMs,
        finishedMs = finishedMs,
        tasks = listOf(
            TaskRunRecord(
                taskId = "t1",
                description = "find app",
                skillId = "find_app",
                tier = "ANDROID_API",
                status = TaskStatus.COMPLETED,
                result = "via ANDROID_API",
                startedMs = 10L,
                finishedMs = 120L
            )
        )
    )

    @Test
    fun `csv emits the header row`() {
        val csv = ExecutionCsv.csv(emptyList())
        val header = csv.lineSequence().first()
        assertEquals(
            "goal_id,goal_description,outcome,task_count,started_ms,completed_ms,duration_ms",
            header
        )
        assertEquals(1, csv.lineSequence().count())
    }

    @Test
    fun `csv renders a full entry row`() {
        val csv = ExecutionCsv.csv(
            listOf(entry("run-1", goalId = "goal-1", startedMs = 1_000L, finishedMs = 5_000L))
        )
        val row = csv.lineSequence().toList()[1]
        assertTrue(row.contains("goal-1"))
        assertTrue(row.contains("open spotify"))
        assertTrue(row.contains("COMPLETED"))
        assertTrue(row.contains("1"))   // task_count
        assertTrue(row.contains("1000"))
        assertTrue(row.contains("5000"))
        assertTrue(row.contains("4000")) // durationMs
    }

    @Test
    fun `csv escapes commas quotes and newlines in description`() {
        val csv = ExecutionCsv.csv(
            listOf(entry("run-1", goalId = "goal-1", description = "a, \"quoted\" and\nnewline"))
        )
        val row = csv.lineSequence().toList()[1]
        // RFC-4180: quoted, internal quotes doubled, and the embedded newline stays inside the field.
        assertTrue(row.startsWith("goal-1"))
        assertTrue(row.contains("\"a, \"\"quoted\"\" and\nnewline\""))
    }

    @Test
    fun `csv preserves input order`() {
        val csv = ExecutionCsv.csv(
            listOf(entry("run-1", goalId = "goal-1"), entry("run-2", goalId = "goal-2"))
        )
        val rows = csv.lineSequence().toList().drop(1)
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains("goal-1"))
        assertTrue(rows[1].contains("goal-2"))
    }

    @Test
    fun `csv leaves optional end times empty for in-flight runs`() {
        val csv = ExecutionCsv.csv(
            listOf(entry("run-1", goalId = "goal-1", startedMs = 1_000L, finishedMs = null))
        )
        val row = csv.lineSequence().toList()[1]
        // completed_ms and duration_ms are empty trailing fields for unfinished runs.
        assertEquals("goal-1,open spotify,COMPLETED,1,1000,,", row)
    }
}
