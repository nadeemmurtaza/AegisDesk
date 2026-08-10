package com.newax.aegis.engine.audit

import com.newax.aegis.engine.intelligence.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Track A8 — the execution audit trail. Pure JVM: the ring-cap append logic and
 * the holder's pre-init safety. (The org.json codec and the kv_store store are
 * Android-side, same pattern as the goal snapshot codec.)
 */
class ExecutionAuditTest {

    private fun entry(
        id: String,
        startedMs: Long = 0L,
        finishedMs: Long? = 1000L,
        outcome: RunOutcome = RunOutcome.COMPLETED
    ) = ExecutionAuditEntry(
        id = id,
        goalId = "g1",
        goalDescription = "open spotify",
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
    fun `append keeps the newest entries within the cap`() {
        val capped = (1..30)
            .map { entry("run-$it", startedMs = it.toLong()) }
            .fold(emptyList<ExecutionAuditEntry>()) { acc, e -> appendAudit(acc, e, maxSize = 10) }

        assertEquals(10, capped.size)
        assertEquals("run-21", capped.first().id)
        assertEquals("run-30", capped.last().id)
    }

    @Test
    fun `append preserves order and passes through small lists`() {
        val result = appendAudit(listOf(entry("a")), entry("b"), maxSize = 10)

        assertEquals(listOf("a", "b"), result.map { it.id })
    }

    @Test
    fun `durations derive from timestamps`() {
        assertEquals(990L, entry("x", startedMs = 10L, finishedMs = 1000L).durationMs)
        assertEquals(110L, entry("x").tasks[0].durationMs)
    }

    @Test
    fun `holder is inert before init`() {
        // No dao in JVM tests: record is a no-op and recent is empty — nothing crashes.
        ExecutionAuditHolder.record(entry("never-recorded"))
        assertTrue(ExecutionAuditHolder.recent(5).isEmpty())
    }
}
