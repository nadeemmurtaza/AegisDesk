package com.newax.aegis.agents

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Stream Dispatcher (skill.sys.mcp_stream) — the universal channel agents
 * use to stream their thoughts, phases, and progress to the user's screen in
 * real time. Instead of each agent implementing its own token/log pipe, every
 * agent calls this one system skill; the UI collects [events] and renders the
 * live session feed. Structured events only — never raw chatter.
 */
object AgentStream {

    /** Structured stream event types — the wire format of the stream. */
    object Type {
        const val TOKEN = "token"                 // live token text
        const val STATUS = "status"               // phase / lifecycle transition
        const val ARTIFACT = "artifact"           // an artifact was produced
        const val ERROR = "error"                 // a structured error block
    }

    data class Event(
        val type: String,
        val sessionId: String,
        val agentId: String,
        val phase: String,
        val text: String,
        val atMs: Long
    )

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    /** Keep the feed bounded — the UI only needs the recent tail. */
    private const val CAP = 200

    fun emit(type: String, sessionId: String, agentId: String, phase: String, text: String) {
        val next = _events.value.toMutableList().apply { add(Event(type, sessionId, agentId, phase, text, System.currentTimeMillis())) }
        _events.value = if (next.size > CAP) next.takeLast(CAP) else next
    }

}
