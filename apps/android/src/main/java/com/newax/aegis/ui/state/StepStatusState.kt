package com.newax.aegis.ui.state

import com.newax.aegis.agents.AgentStream

/**
 * The inline step block (slice 12, spec §7.2) — the plain-Kotlin half of the
 * running-task surface in the chat thread.
 *
 * [com.newax.aegis.agents.AgentStream] is a typed TOKEN / STATUS / ARTIFACT /
 * ERROR bus whose events carry the run's session id. This holder groups the
 * events of one session into ordered step rows: STATUS lines are running steps,
 * an ARTIFACT is the completed result, an ERROR is a failed step. TOKEN chunks
 * are streamed model text, not steps, and are ignored here (they render in the
 * streaming bubble).
 *
 * Stateless: the bus lives in the agents layer; the screen renders the rows.
 */
class StepStatusState {

    enum class RunPhase { RUNNING, DONE, FAILED }

    data class StepRow(val phase: RunPhase, val title: String)

    /**
     * The ordered steps of one session, oldest first. Empty when the session
     * has emitted nothing yet (the block shows only its header) or when the
     * session id is unknown.
     */
    fun rowsFor(events: List<AgentStream.Event>, sessionId: String): List<StepRow> {
        if (sessionId.isBlank()) return emptyList()
        val rows = mutableListOf<StepRow>()
        for (e in events) {
            if (e.sessionId != sessionId) continue
            when (e.type) {
                AgentStream.Type.STATUS -> rows += StepRow(RunPhase.RUNNING, e.text)
                AgentStream.Type.ARTIFACT -> rows += StepRow(RunPhase.DONE, e.text)
                AgentStream.Type.ERROR -> rows += StepRow(RunPhase.FAILED, e.text)
                else -> Unit // TOKEN is model text, not a step
            }
        }
        return rows
    }
}
