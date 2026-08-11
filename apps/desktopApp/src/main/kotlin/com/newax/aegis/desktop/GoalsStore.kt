package com.newax.aegis.desktop

import com.newax.aegis.desktop.planner.Goal
import com.newax.aegis.desktop.planner.GoalState
import com.newax.aegis.desktop.planner.TaskGraph
import com.newax.aegis.desktop.planner.TaskNode
import com.newax.aegis.desktop.planner.TaskStatus
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * The stored per-goal plan pre-flight verdict. The plan's goal and task list are
 * already the snapshot's [Goal]s/[TaskGraph]s — persisting them again would
 * duplicate data — so only the verdict fields round-trip; [DesktopGoalPlanner.restore]
 * rebuilds the [com.newax.aegis.desktop.planner.DesktopPlan] from goal + graph.
 */
data class PlanVerdict(
    val feasible: Boolean,
    val missingSkills: List<String>,
    val missingCapabilities: List<String>,
    val warnings: List<String>,
)

/**
 * Everything the desktop session persists across restarts (Phase B3): goals,
 * task graphs, state machines, plan verdicts, and the execution audit trail.
 * A corrupt or missing snapshot is an honest empty start (AGENTS.md R9) — the
 * store never fabricates data and never crashes the app on a bad file.
 */
data class GoalsSnapshot(
    val goals: List<Goal>,
    val graphs: List<TaskGraph>,
    val states: Map<String, GoalState>,
    val plans: Map<String, PlanVerdict>,
    val runs: List<ExecutionAuditEntry>,
)

/** Goals/audit persistence boundary — the desktop twin of Android's kv_store snapshots. */
interface GoalsStore {
    fun save(snapshot: GoalsSnapshot)
    fun load(): GoalsSnapshot?
}

/**
 * JSON snapshot store under `~/.aegis/goals.json` (the desktopApp-owned store;
 * `shared/database` stays frozen per the parallel split). Writes are atomic
 * (temp file + move) so a crash mid-write can never corrupt the previous
 * snapshot; [save] is best-effort — a failed write logs and keeps the in-memory
 * planner as the session's source of truth instead of killing a run. [load]
 * returns null for a missing, unreadable, unsupported-version, or corrupt file.
 */
class FileGoalsStore(private val file: Path) : GoalsStore {

    constructor() : this(defaultFile())

    companion object {
        const val SCHEMA_VERSION = 1

        fun defaultFile(): Path =
            Paths.get(System.getProperty("user.home") ?: ".", ".aegis", "goals.json")
    }

    override fun save(snapshot: GoalsSnapshot) {
        try {
            file.parent?.let { Files.createDirectories(it) }
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.write(tmp, snapshot.toJson().toByteArray(Charsets.UTF_8))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            // Best-effort persistence (named failure mode): a failed save must
            // never take down a run — the in-memory planner stays authoritative
            // for this session and the next successful save catches up.
            println("[goals] save failed ($file): ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun load(): GoalsSnapshot? {
        if (!Files.isRegularFile(file)) return null
        return try {
            val root = JSONObject(Files.readString(file))
            if (root.optInt("version", -1) != SCHEMA_VERSION) return null
            root.toSnapshot()
        } catch (_: IOException) {
            null // unreadable file → honest empty start
        } catch (_: JSONException) {
            null // corrupt file → honest empty start (never a crash)
        }
    }

    // ── Serialization ────────────────────────────────────────────────────────

    private fun GoalsSnapshot.toJson(): String {
        val root = JSONObject().put("version", SCHEMA_VERSION)

        val goalsArr = JSONArray()
        goals.forEach { g ->
            goalsArr.put(
                JSONObject()
                    .put("id", g.id)
                    .put("description", g.description)
                    .put("intent", g.intent)
                    .put("priority", g.priority)
                    .put("createdMs", g.createdMs)
            )
        }
        root.put("goals", goalsArr)

        val graphsArr = JSONArray()
        graphs.forEach { graph ->
            val tasksArr = JSONArray()
            graph.tasks.forEach { t ->
                tasksArr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("goalId", t.goalId)
                        .put("description", t.description)
                        .putOpt("skillId", t.skillId)
                        .put("dependencies", JSONArray(t.dependencies))
                        .put("status", t.status.name)
                        .putOpt("result", t.result)
                        .putOpt("startedMs", t.startedMs)
                        .putOpt("completedMs", t.completedMs)
                        .putOpt("failureKind", t.failureKind?.name)
                )
            }
            graphsArr.put(
                JSONObject()
                    .put("goalId", graph.goalId)
                    .put("createdMs", graph.createdMs)
                    .put("tasks", tasksArr)
            )
        }
        root.put("graphs", graphsArr)

        val statesObj = JSONObject()
        states.forEach { (id, state) -> statesObj.put(id, state.name) }
        root.put("states", statesObj)

        val plansObj = JSONObject()
        plans.forEach { (id, verdict) ->
            plansObj.put(
                id,
                JSONObject()
                    .put("feasible", verdict.feasible)
                    .put("missingSkills", JSONArray(verdict.missingSkills))
                    .put("missingCapabilities", JSONArray(verdict.missingCapabilities))
                    .put("warnings", JSONArray(verdict.warnings))
            )
        }
        root.put("plans", plansObj)

        val runsArr = JSONArray()
        runs.forEach { r ->
            runsArr.put(
                JSONObject()
                    .put("goalId", r.goalId)
                    .put("goalDescription", r.goalDescription)
                    .put("outcome", r.outcome)
                    .putOpt("reason", r.reason)
                    .put("tiers", JSONArray(r.tiers))
                    .put("taskCount", r.taskCount)
                    .put("startedMs", r.startedMs)
                    .put("completedMs", r.completedMs)
            )
        }
        root.put("runs", runsArr)

        return root.toString()
    }

    // ── Deserialization ──────────────────────────────────────────────────────

    private fun JSONObject.toSnapshot(): GoalsSnapshot {
        val goals = getJSONArray("goals").mapObjects { o ->
            Goal(
                id = o.getString("id"),
                description = o.getString("description"),
                intent = o.getString("intent"),
                priority = o.getInt("priority"),
                createdMs = o.getLong("createdMs"),
            )
        }
        val graphs = getJSONArray("graphs").mapObjects { o ->
            val tasks = o.getJSONArray("tasks").mapObjects { t ->
                TaskNode(
                    id = t.getString("id"),
                    goalId = t.getString("goalId"),
                    description = t.getString("description"),
                    skillId = t.optString("skillId").ifEmpty { null },
                    dependencies = t.getJSONArray("dependencies").mapStrings(),
                    status = TaskStatus.valueOf(t.getString("status")),
                    result = t.optString("result").ifEmpty { null },
                    startedMs = t.optLongOrNull("startedMs"),
                    completedMs = t.optLongOrNull("completedMs"),
                    // Missing/unknown decodes to null — old B3 snapshots predating
                    // the policy gate still load cleanly.
                    failureKind = TaskFailureKind.entries.firstOrNull {
                        it.name == t.optString("failureKind")
                    },
                )
            }
            TaskGraph(goalId = o.getString("goalId"), tasks = tasks, createdMs = o.getLong("createdMs"))
        }
        val states = getJSONObject("states").mapToMap { key, o -> key to GoalState.valueOf(o.getString(key)) }
        val plans = getJSONObject("plans").mapToMap { key, o ->
            val p = o.getJSONObject(key)
            key to PlanVerdict(
                feasible = p.getBoolean("feasible"),
                missingSkills = p.getStringList("missingSkills"),
                missingCapabilities = p.getStringList("missingCapabilities"),
                warnings = p.getStringList("warnings"),
            )
        }
        val runs = getJSONArray("runs").mapObjects { r ->
            ExecutionAuditEntry(
                goalId = r.getString("goalId"),
                goalDescription = r.getString("goalDescription"),
                outcome = r.getString("outcome"),
                reason = r.optString("reason").ifEmpty { null },
                tiers = r.getJSONArray("tiers").mapStrings(),
                taskCount = r.getInt("taskCount"),
                startedMs = r.getLong("startedMs"),
                completedMs = r.getLong("completedMs"),
            )
        }
        return GoalsSnapshot(goals, graphs, states, plans, runs)
    }

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { transform(getJSONObject(it)) }

    private fun JSONArray.mapStrings(): List<String> = (0 until length()).map { getString(it) }

    private fun JSONObject.getStringList(key: String): List<String> =
        getJSONArray(key).mapStrings()

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun <T> JSONObject.mapToMap(transform: (String, JSONObject) -> Pair<String, T>): Map<String, T> =
        keys().asSequence().associate { key -> transform(key, this) }
}
