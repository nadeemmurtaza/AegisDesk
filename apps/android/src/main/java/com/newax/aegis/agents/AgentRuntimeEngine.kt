package com.newax.aegis.agents

import android.content.Context
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.db.entity.*
import com.newax.aegis.memory.AgentMemory
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/**
 * The agent runtime engine (docs/AGENTS_DESIGN.md §runtime — the PRAM
 * framework's Action + Memory pillars made real). One engine serves every
 * agent through the SAME [AgentController] contract:
 *
 *  - run / abort / status / healthCheck (get_status) — the encapsulated
 *    standard interface, [controllerFor] per agent,
 *  - the run ledger ([AgentSessionEntity]) with live phases — "Planning" →
 *    "Thinking" → "Running Tool" → "Done" — streamed to the UI through
 *    [AgentStream] (skill.sys.mcp_stream),
 *  - strict structured output: [AgentResult.success]/[AgentResult.error]
 *    blocks, never raw chatter,
 *  - freeze/thaw via [StateArchiver] (skill.sys.serialize_state),
 *  - the health audit (skill.sys.health_audit): real integrity checks
 *    (database reachable, agent record, package, skills, stale sessions).
 *    A FAULTED agent is QUARANTINED — auto-disabled — and a human restores
 *    it through [recover]. Soft issues (missing package, missing skill,
 *    stale crash residue) are DEGRADED and monitored, never disabled.
 *
 * One shared model serves all agents; the per-agent brain is the role context
 * + permitted skills. Sessions are device-local (never synced).
 */
object AgentRuntimeEngine {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        StateArchiver.init(context)
    }

    // ── the encapsulated interface ──────────────────────────────────────────

    private class RuntimeController(private val agentId: String) : AgentController {
        override fun run(taskPrompt: String, context: AgentContext): String =
            startSession(agentId, taskPrompt, context.toJson())

        override fun abort() {
            activeSessions().filter { it.agentId == agentId }.forEach { abortSession(it.sessionId) }
        }

        override fun status(): AgentStatus? =
            activeSessions().firstOrNull { it.agentId == agentId }?.let { status(it.sessionId) }

        override fun healthCheck(): HealthReport = healthCheck(agentId)
    }

    /** Every agent gets the same controller — the encapsulated interface. */
    fun controllerFor(agentId: String): AgentController = RuntimeController(agentId)

    // ── run ledger ──────────────────────────────────────────────────────────

    fun start(agentId: String, taskPrompt: String, contextJson: String = "{}"): String =
        controllerFor(agentId).run(taskPrompt, AgentContext.fromJson(contextJson))

    private fun startSession(agentId: String, taskPrompt: String, contextJson: String): String {
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val db = runCatching { NewaxDatabase.get }.getOrNull()
        if (db != null) {
            runBlocking {
                runCatching {
                    db.agentRuntimeDao().upsertSession(
                        AgentSessionEntity(
                            sessionId = sessionId, agentId = agentId,
                            status = SessionStatus.RUNNING, phase = SessionPhase.PLANNING,
                            taskPrompt = taskPrompt.take(2000), contextJson = contextJson.take(4000),
                            startedAtMs = now, updatedAtMs = now
                        )
                    )
                }
            }
        }
        AgentStream.emit(AgentStream.Type.STATUS, sessionId, agentId, SessionPhase.PLANNING, "Task started")
        return sessionId
    }

    /** The live phase metric — get_status()'s "what is it doing NOW". */
    fun phase(sessionId: String, phase: String) {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        val agentId = runBlocking { runCatching { db.agentRuntimeDao().sessionById(sessionId)?.agentId }.getOrNull() } ?: return
        runBlocking { runCatching { db.agentRuntimeDao().setPhase(sessionId, phase, System.currentTimeMillis()) } }
        AgentStream.emit(AgentStream.Type.STATUS, sessionId, agentId, phase, "Phase: $phase")
    }

    /** The task finished — write the strict success block. */
    fun complete(sessionId: String, summary: String, artifactPath: String = "") {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        val agentId = runBlocking { runCatching { db.agentRuntimeDao().sessionById(sessionId)?.agentId }.getOrNull() } ?: return
        runBlocking { runCatching { db.agentRuntimeDao().setResult(sessionId, AgentResult.success(summary, artifactPath), System.currentTimeMillis()) } }
        AgentStream.emit(AgentStream.Type.ARTIFACT, sessionId, agentId, SessionPhase.DONE, "Completed: ${summary.take(120)}")
    }

    /**
     * The task failed — write the strict error block, then run the health
     * audit: a real agent fault (broken DB, missing record) quarantines the
     * agent; a transient model error finds the agent HEALTHY and changes
     * nothing.
     */
    fun fail(sessionId: String, errorType: String, message: String) {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        val agentId = runBlocking { runCatching { db.agentRuntimeDao().sessionById(sessionId)?.agentId }.getOrNull() } ?: return
        runBlocking { runCatching { db.agentRuntimeDao().setError(sessionId, AgentResult.error(errorType, message.take(500)), System.currentTimeMillis()) } }
        AgentStream.emit(AgentStream.Type.ERROR, sessionId, agentId, SessionPhase.DONE, "Failed [$errorType]: ${message.take(120)}")
        runCatching { healthCheck(agentId) }
    }

    /** Force-stop — the user's Cancel. Writes the uniform USER_ABORT error block. */
    fun abortSession(sessionId: String) {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        val agentId = runBlocking { runCatching { db.agentRuntimeDao().sessionById(sessionId)?.agentId }.getOrNull() } ?: return
        runBlocking { runCatching { db.agentRuntimeDao().setAborted(sessionId, AgentResult.error(AgentErrorType.USER_ABORT, "Aborted by user"), System.currentTimeMillis()) } }
        AgentStream.emit(AgentStream.Type.STATUS, sessionId, agentId, SessionPhase.DONE, "Aborted by user")
    }

    fun status(sessionId: String): AgentStatus? {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return null
        return runBlocking { runCatching { db.agentRuntimeDao().sessionById(sessionId)?.let { AgentStatus.from(it) } }.getOrNull() }
    }

    fun activeSessions(): List<AgentSessionEntity> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.agentRuntimeDao().activeSessions() }.getOrDefault(emptyList()) }
    }

    fun recentSessions(limit: Int = 50): List<AgentSessionEntity> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.agentRuntimeDao().recentSessions(limit) }.getOrDefault(emptyList()) }
    }

    fun frozenSessions(): List<AgentSessionEntity> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.agentRuntimeDao().frozenSessions() }.getOrDefault(emptyList()) }
    }

    // ── freeze / thaw (skill.sys.serialize_state) ───────────────────────────

    /** Serialize a running session to disk and mark it FROZEN. */
    fun freeze(sessionId: String): Boolean {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return false
        val session = runBlocking { runCatching { db.agentRuntimeDao().sessionById(sessionId) }.getOrNull() } ?: return false
        if (session.status == SessionStatus.FROZEN) return true
        val file = StateArchiver.freeze(session) ?: return false
        runBlocking { runCatching { db.agentRuntimeDao().setFrozen(sessionId, file.absolutePath, System.currentTimeMillis()) } }
        AgentStream.emit(AgentStream.Type.STATUS, sessionId, session.agentId, SessionPhase.RESTORED, "Frozen to ${file.name}")
        return true
    }

    /**
     * Restore the agent's most recent frozen state as a new RUNNING session
     * (phase "Restored") carrying the original task + context — the task can
     * then be re-run from the UI. Returns the new session id, or null when
     * there is nothing frozen.
     */
    fun thaw(agentId: String): String? {
        val state = StateArchiver.latestFor(agentId) ?: return null
        // Restored as a RUNNING session (phase "Restored") carrying the original
        // task + context — NOT completed. The frozen payload stays on disk as the
        // archive; the user continues the task from the UI.
        val sessionId = startSession(agentId, state.taskPrompt, state.contextJson)
        phase(sessionId, SessionPhase.RESTORED)
        AgentStream.emit(AgentStream.Type.STATUS, sessionId, agentId, SessionPhase.RESTORED, "Thawed from freeze — task ready to continue")
        return sessionId
    }

    // ── health audit (skill.sys.health_audit) ───────────────────────────────

    private const val STALE_SESSION_MS = 2L * 60 * 60 * 1000L // 2h — a crash left this running

    /**
     * The integrity audit. Checks (each finding is a named, honest check):
     *   1. database reachable + session table writable,
     *   2. agent record exists (the registry row),
     *   3. zip-imported agents: the extracted package directory exists,
     *   4. granted skills still resolve (a broken grant is a fault),
     *   5. no stale RUNNING sessions (crash residue).
     * Hard findings (DB/record) → FAULTED → QUARANTINE: the agent is
     * auto-disabled and an episode records the fault. Soft findings → DEGRADED
     * (monitored, not disabled). Clean → HEALTHY.
     */
    fun healthCheck(agentId: String): HealthReport {
        val db = runCatching { NewaxDatabase.get }.getOrNull()
        val now = System.currentTimeMillis()
        val findings = mutableListOf<String>()

        val dao = db?.agentRuntimeDao()
        val dbOk = dao != null && runBlocking { runCatching { dao.sessionById("__health_probe__") }.isSuccess }
        if (!dbOk) findings += "database-unreachable"

        val agent = if (dbOk && db != null)
            runBlocking { runCatching { db.agentRegistryDao().byId(agentId) }.getOrNull() } else null
        if (agent == null) findings += "agent-record-missing"

        if (agent != null && agent.source != "builtin" && agent.packageDir.isNotBlank()) {
            if (!File(agent.packageDir).exists()) findings += "package-missing"
        }

        if (agent != null && db != null) {
            val granted = runCatching { SkillManager.grantedSkillIds(agentId) }.getOrDefault(emptySet())
            granted.forEach { sid ->
                if (runBlocking { runCatching { db.skillManagerDao().skillById(sid) }.getOrNull() } == null) findings += "skill-missing:$sid"
            }
        }

        if (dbOk && db != null) {
            val stale = runBlocking { runCatching { db.agentRuntimeDao().markStaleRunning(now - STALE_SESSION_MS) }.getOrDefault(0) }
            if (stale > 0) findings += "stale-session:$stale"
        }

        val hard = findings.any { it.startsWith("database-unreachable") || it.startsWith("agent-record-missing") }
        val status = when {
            hard -> AgentHealthStatus.FAULTED
            findings.isNotEmpty() -> AgentHealthStatus.DEGRADED
            else -> AgentHealthStatus.HEALTHY
        }
        val previous = if (db != null) runBlocking { runCatching { db.agentRuntimeDao().healthById(agentId) }.getOrNull() } else null
        val faultCount = (previous?.faultCount ?: 0) + if (status == AgentHealthStatus.FAULTED) 1 else 0

        val action = when {
            status == AgentHealthStatus.FAULTED -> {
                runCatching { AgentRegistry.setEnabled(agentId, false) } // quarantine
                "auto-disabled (quarantine)"
            }
            status == AgentHealthStatus.DEGRADED -> "monitor"
            else -> "none"
        }

        if (db != null) {
            runBlocking {
                runCatching {
                    db.agentRuntimeDao().upsertHealth(
                        AgentHealthEntity(
                            agentId = agentId, status = status,
                            detail = findings.joinToString(", "), faultCount = faultCount,
                            actionTaken = action, lastCheckAtMs = now,
                            lastRecoveredAtMs = previous?.lastRecoveredAtMs ?: 0
                        )
                    )
                }
            }
        }

        val report = HealthReport(agentId, status, findings.joinToString(", "), faultCount, action, now)
        // Episodic record: FAULTED always; other transitions once (avoid spam).
        val changed = previous?.status != status
        if (status == AgentHealthStatus.FAULTED || (changed && previous != null)) {
            runCatching {
                AgentMemory.recordEpisode(
                    agentId = "agent:$agentId",
                    category = "health",
                    summary = "Health audit: $status — ${report.detail.ifBlank { "all checks passed" }}. $action",
                    outcome = if (status == AgentHealthStatus.HEALTHY) EpisodeOutcome.SUCCESS else EpisodeOutcome.FAILURE,
                    lesson = if (status == AgentHealthStatus.FAULTED) "Agent quarantined after integrity failure — restore it from the Agents screen." else "",
                    contextRef = "health:$now"
                )
            }
        }
        AgentStream.emit(AgentStream.Type.STATUS, "health:$agentId", agentId, SessionPhase.DONE, "Health: $status — ${report.detail.ifBlank { "all checks passed" }}")
        return report
    }

    /** The human's Restore — clears the fault, re-enables the agent. */
    fun recover(agentId: String) {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        runBlocking {
            runCatching {
                db.agentRuntimeDao().upsertHealth(
                    AgentHealthEntity(
                        agentId = agentId, status = AgentHealthStatus.HEALTHY,
                        detail = "recovered by user", faultCount = 0,
                        actionTaken = "re-enabled", lastCheckAtMs = now, lastRecoveredAtMs = now
                    )
                )
                db.agentRegistryDao().setEnabled(agentId, true)
            }
        }
        AgentStream.emit(AgentStream.Type.STATUS, "health:$agentId", agentId, SessionPhase.DONE, "Restored by user — agent re-enabled")
    }

    fun allHealth(): List<AgentHealthEntity> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.agentRuntimeDao().allHealth() }.getOrDefault(emptyList()) }
    }
}
