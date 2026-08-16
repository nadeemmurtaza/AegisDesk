package com.newax.aegis.ui.state

import com.newax.aegis.agents.AgentStream
import com.newax.aegis.ui.state.StepStatusState.RunPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T3.5e — the inline step block's mapping (slice 12, spec §7.2): AgentStream
 * events for one session become ordered step rows. STATUS lines are running
 * steps, ARTIFACT the completed result, ERROR a failure; TOKEN chunks are
 * streamed model text and are not steps.
 */
class StepStatusStateTest {

    private val state = StepStatusState()

    private fun event(type: String, sessionId: String = "s1", text: String = "t", atMs: Long = 0L) =
        AgentStream.Event(type, sessionId, "agent-a", "PLANNING", text, atMs)

    @Test
    fun `blank session yields no rows`() {
        assertEquals(emptyList(), state.rowsFor(emptyList(), ""))
        assertEquals(emptyList(), state.rowsFor(emptyList(), "  "))
    }

    @Test
    fun `only the session's own events are rendered`() {
        val events = listOf(
            event(AgentStream.Type.STATUS, sessionId = "s1", text = "Task started"),
            event(AgentStream.Type.STATUS, sessionId = "s2", text = "Other task"),
            event(AgentStream.Type.ARTIFACT, sessionId = "s1", text = "Done")
        )
        val rows = state.rowsFor(events, "s1")
        assertEquals(listOf("Task started", "Done"), rows.map { it.title })
        assertEquals(listOf(RunPhase.RUNNING, RunPhase.DONE), rows.map { it.phase })
    }

    @Test
    fun `status is a running step and artifact is the done result`() {
        val rows = state.rowsFor(
            listOf(
                event(AgentStream.Type.STATUS, text = "Phase: PLANNING"),
                event(AgentStream.Type.ARTIFACT, text = "Completed: summary")
            ),
            "s1"
        )
        assertEquals(RunPhase.RUNNING, rows[0].phase)
        assertEquals(RunPhase.DONE, rows[1].phase)
    }

    @Test
    fun `error maps to a failed step`() {
        val rows = state.rowsFor(listOf(event(AgentStream.Type.ERROR, text = "Failed [MODEL_ERROR]: boom")), "s1")
        assertEquals(RunPhase.FAILED, rows.single().phase)
        assertEquals("Failed [MODEL_ERROR]: boom", rows.single().title)
    }

    @Test
    fun `token chunks are not steps`() {
        val rows = state.rowsFor(
            listOf(
                event(AgentStream.Type.TOKEN, text = "chunk one"),
                event(AgentStream.Type.STATUS, text = "Task started")
            ),
            "s1"
        )
        assertEquals(listOf("Task started"), rows.map { it.title })
    }

    @Test
    fun `order is preserved oldest-first`() {
        val rows = state.rowsFor(
            listOf(
                event(AgentStream.Type.STATUS, text = "first", atMs = 1),
                event(AgentStream.Type.STATUS, text = "second", atMs = 2),
                event(AgentStream.Type.STATUS, text = "third", atMs = 3)
            ),
            "s1"
        )
        assertEquals(listOf("first", "second", "third"), rows.map { it.title })
    }
}
