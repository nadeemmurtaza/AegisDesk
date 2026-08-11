package com.newax.aegis.agents

import com.newax.aegis.db.entity.AgentHealthEntity
import com.newax.aegis.db.entity.AgentSessionEntity
import org.json.JSONObject

/**
 * The ENCAPSULATED standard agent interface (docs/AGENTS_DESIGN.md §runtime) —
 * every agent, built-in or imported, exposes the EXACT same four operations.
 * No agent invents its own surface (a coding agent using run() while a
 * research agent uses execute() would collapse the system):
 *
 *  - [run]          — start execution with the user payload + context,
 *  - [abort]        — force-stop execution (user hits Cancel),
 *  - [status]       — get_status(): live runtime metrics (phase — "Thinking",
 *                     "Running Tool" — plus the structured result/error),
 *  - [healthCheck]  — verify the agent's internal state is not corrupted
 *                     (skill.sys.health_audit).
 *
 * Agents never return raw, unpredictable chatter: they exchange STRICT JSON
 * blocks ([AgentResult.success] / [AgentResult.error]) and stream progress
 * through [AgentStream] (skill.sys.mcp_stream). One shared model serves every
 * agent — the per-agent "brain" is the role context + this controller.
 */
interface AgentController {

    /** Start execution of a task — returns the new session id (RUNNING). */
    fun run(taskPrompt: String, context: AgentContext): String

    /** Force-stop the agent's active session(s) — the user's Cancel. */
    fun abort()

    /** get_status(): the live runtime metrics of the agent's active session. */
    fun status(): AgentStatus?

    /** Verify internal state integrity — HEALTHY / DEGRADED / FAULTED. */
    fun healthCheck(): HealthReport
}

/** What [AgentController.run] receives — the payload + the scoped context. */
data class AgentContext(
    val taskPrompt: String,
    /** The orchestration plan summary (which steps, which dominants). */
    val planSummary: String = "",
    /** Memory layer pointers the agent may consult (library / episodes / handoffs). */
    val memoryPointers: List<String> = emptyList(),
    /** The skills in scope for this run (permitted + global). */
    val skills: List<String> = emptyList()
) {
    fun toJson(): String = JSONObject().apply {
        put("task_prompt", taskPrompt)
        put("plan_summary", planSummary)
        put("memory_pointers", memoryPointers)
        put("skills", skills)
    }.toString()

    companion object {
        fun fromJson(json: String): AgentContext = runCatching {
            val o = JSONObject(json)
            val arr = { key: String -> o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList() }
            AgentContext(
                taskPrompt = o.optString("task_prompt"),
                planSummary = o.optString("plan_summary"),
                memoryPointers = arr("memory_pointers"),
                skills = arr("skills")
            )
        }.getOrDefault(AgentContext(""))
    }
}

/** The live runtime metrics — what get_status() returns. */
data class AgentStatus(
    val sessionId: String,
    val agentId: String,
    val status: String,
    val phase: String,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val resultJson: String = "",
    val errorJson: String = ""
) {
    companion object {
        fun from(session: AgentSessionEntity) = AgentStatus(
            sessionId = session.sessionId,
            agentId = session.agentId,
            status = session.status,
            phase = session.phase,
            startedAtMs = session.startedAtMs,
            updatedAtMs = session.updatedAtMs,
            resultJson = session.resultJson,
            errorJson = session.errorJson
        )
    }
}

/** The outcome of a health audit (skill.sys.health_audit). */
data class HealthReport(
    val agentId: String,
    val status: String,
    val detail: String,
    val faultCount: Int,
    val actionTaken: String,
    val checkedAtMs: Long
) {
    companion object {
        fun from(h: AgentHealthEntity) = HealthReport(
            agentId = h.agentId, status = h.status, detail = h.detail,
            faultCount = h.faultCount, actionTaken = h.actionTaken, checkedAtMs = h.lastCheckAtMs
        )
    }
}

/**
 * The strict structured output contract — agents return blocks, never chatter.
 *
 *  Success: {"status":"success","artifact_path":"…","summary":"…"}
 *  Error:   {"status":"error","error_type":"PERMISSION_DENIAL","message":"…"}
 *
 * The core app reads these blocks to handle results and errors cleanly in the UI.
 */
object AgentResult {

    data class Parsed(val status: String, val summary: String, val artifactPath: String, val errorType: String, val message: String)

    fun success(summary: String, artifactPath: String = ""): String = JSONObject().apply {
        put("status", "success")
        put("artifact_path", artifactPath)
        put("summary", summary)
    }.toString()

    fun error(errorType: String, message: String): String = JSONObject().apply {
        put("status", "error")
        put("error_type", errorType)
        put("message", message)
    }.toString()

    fun parse(json: String): Parsed? = runCatching {
        val o = JSONObject(json)
        Parsed(
            status = o.optString("status"),
            summary = o.optString("summary"),
            artifactPath = o.optString("artifact_path"),
            errorType = o.optString("error_type"),
            message = o.optString("message")
        )
    }.getOrNull()
}
