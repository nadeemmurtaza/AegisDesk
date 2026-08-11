package com.newax.aegis.agents

import android.content.Context
import com.newax.aegis.db.entity.AgentSessionEntity
import org.json.JSONObject
import java.io.File

/**
 * The State Archiver (skill.sys.serialize_state) — every agent's built-in
 * freeze/thaw mechanism. The central app can serialize an agent's current
 * working session (task prompt, scoped context, result-so-far) to local disk
 * and restore it later without losing the running task's context.
 *
 * Payloads are JSON in app-private storage (`filesDir/agent-state/`), named
 * `<agentId>-<sessionId>.json` so thaw can enumerate per agent. App-private
 * storage is device-encrypted at rest (Android FBE) — the payload never
 * leaves the device. Writes are atomic (temp file + rename) so a crash mid-
 * freeze cannot corrupt a payload.
 */
object StateArchiver {

    /** The serialized working state of one session. */
    data class FrozenState(
        val sessionId: String,
        val agentId: String,
        val taskPrompt: String,
        val contextJson: String,
        val resultJson: String,
        val frozenAtMs: Long
    )

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    private fun context(): Context = requireNotNull(appContext) { "StateArchiver.init(context) not called" }

    private fun root(): File = File(context().filesDir, "agent-state").apply { mkdirs() }

    /** Freeze a session — write its state to disk. Null on any failure. */
    fun freeze(session: AgentSessionEntity): File? {
        val payload = JSONObject().apply {
            put("session_id", session.sessionId)
            put("agent_id", session.agentId)
            put("task_prompt", session.taskPrompt)
            put("context_json", session.contextJson)
            put("result_json", session.resultJson)
            put("frozen_at_ms", System.currentTimeMillis())
        }
        val dest = File(root(), "${session.agentId}-${session.sessionId}.json")
        val tmp = File(root(), dest.name + ".tmp")
        return runCatching {
            tmp.writeText(payload.toString(), Charsets.UTF_8)
            if (tmp.renameTo(dest)) dest else { dest.writeText(payload.toString(), Charsets.UTF_8); dest }
        }.getOrNull()
    }

    private fun parse(file: File): FrozenState? = runCatching {
        val o = JSONObject(file.readText(Charsets.UTF_8))
        FrozenState(
            sessionId = o.optString("session_id"),
            agentId = o.optString("agent_id"),
            taskPrompt = o.optString("task_prompt"),
            contextJson = o.optString("context_json"),
            resultJson = o.optString("result_json"),
            frozenAtMs = o.optLong("frozen_at_ms")
        )
    }.getOrNull()

    /** The most recent frozen state for an agent — the thaw source. */
    fun latestFor(agentId: String): FrozenState? =
        root().listFiles()
            ?.filter { it.isFile && it.name.startsWith("$agentId-") && it.name.endsWith(".json") }
            ?.mapNotNull { parse(it) }
            ?.maxByOrNull { it.frozenAtMs }
}
