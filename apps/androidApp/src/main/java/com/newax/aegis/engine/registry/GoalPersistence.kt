package com.newax.aegis.engine.registry

import com.newax.aegis.db.dao.KvStoreDao
import com.newax.aegis.db.entity.KvStoreEntity
import com.newax.aegis.engine.intelligence.GoalPlanner
import com.newax.aegis.engine.intelligence.GoalSnapshot
import com.newax.aegis.engine.intelligence.PlanResult
import com.newax.aegis.engine.intelligence.TaskFailureKind
import com.newax.aegis.engine.intelligence.TaskGraph
import com.newax.aegis.engine.intelligence.TaskNode
import com.newax.aegis.engine.intelligence.TaskStatus
import com.newax.aegis.engine.state.GoalState
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Goal persistence — Track A5. Goals, their task graphs (with live task statuses
 * and results), goal-level states and plan pre-flights are captured as
 * [GoalSnapshot]s, encoded to JSON, and stored in the existing kv_store table —
 * no schema change, no migration, the shared:database contract stays frozen.
 *
 * The JSON codec uses org.json, the same serialization convention the rest of
 * the app's structured persistence (BackupManager, KnowledgeGraph, ProjectTracker)
 * already follows. Corruption anywhere in a stored snapshot is a named, handled
 * failure mode: the snapshot is dropped and the planner starts empty rather than
 * crashing bootstrap.
 */
object GoalSnapshotCodec {

    private const val VERSION = 1
    private const val KEY_VERSION = "v"
    private const val KEY_GOALS = "goals"

    fun encode(snapshots: List<GoalSnapshot>): String =
        JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_GOALS, JSONArray(snapshots.map { encodeSnapshot(it) }))
            .toString()

    /** Returns null when the stored JSON is corrupt or from an unknown format. */
    fun decode(json: String): List<GoalSnapshot>? = try {
        val root = JSONObject(json)
        if (root.optInt(KEY_VERSION, 0) != VERSION) return null
        val arr = root.optJSONArray(KEY_GOALS) ?: return null
        (0 until arr.length()).map { idx -> decodeSnapshot(arr.getJSONObject(idx)) }
    } catch (_: JSONException) {
        null
    }

    private fun encodeSnapshot(s: GoalSnapshot): JSONObject =
        JSONObject()
            .put("goal", encodeGoal(s.goal))
            .put("graph", encodeGraph(s.graph))
            .put("state", s.state.name)
            .put("plan", s.plan?.let { encodePlan(it) } ?: JSONObject.NULL)

    private fun encodeGoal(g: com.newax.aegis.engine.intelligence.Goal): JSONObject =
        JSONObject()
            .put("id", g.id)
            .put("description", g.description)
            .put("intent", g.intent)
            .put("subGoals", JSONArray(g.subGoals))
            .put("requiredSkills", JSONArray(g.requiredSkills))
            .put("priority", g.priority)
            .put("createdMs", g.createdMs)
            .put("deadlineMs", g.deadlineMs)
            .put("tags", JSONArray(g.tags))

    private fun encodeGraph(g: TaskGraph): JSONObject =
        JSONObject()
            .put("goalId", g.goalId)
            .put("createdMs", g.createdMs)
            .put("tasks", JSONArray(g.tasks.map { encodeTask(it) }))

    private fun encodeTask(t: TaskNode): JSONObject =
        JSONObject()
            .put("id", t.id)
            .put("goalId", t.goalId)
            .put("description", t.description)
            .put("skillId", t.skillId)
            .put("dependencies", JSONArray(t.dependencies))
            .put("estimatedMs", t.estimatedMs)
            .put("status", t.status.name)
            .put("result", t.result)
            .put("startedMs", t.startedMs)
            .put("completedMs", t.completedMs)
            .put("failureKind", t.failureKind?.name)

    private fun encodePlan(p: PlanResult): JSONObject =
        JSONObject()
            .put("feasible", p.feasible)
            .put("missingSkills", JSONArray(p.missingSkills))
            .put("missingCapabilities", JSONArray(p.missingCapabilities))
            .put("warnings", JSONArray(p.warnings))

    private fun decodeSnapshot(o: JSONObject): GoalSnapshot {
        val state = GoalState.entries.firstOrNull { it.name == o.optString("state") }
            ?: GoalState.OPEN
        val goal = decodeGoal(o.getJSONObject("goal"))
        val graph = decodeGraph(o.getJSONObject("graph"))
        // PlanResult carries the same goal/graph again — reuse the snapshot-level ones.
        val plan = if (o.isNull("plan")) null else decodePlan(o.getJSONObject("plan"), goal, graph)
        return GoalSnapshot(
            goal = goal,
            graph = graph,
            state = state,
            plan = plan
        )
    }

    private fun decodeGoal(o: JSONObject): com.newax.aegis.engine.intelligence.Goal =
        com.newax.aegis.engine.intelligence.Goal(
            id = o.optString("id"),
            description = o.optString("description"),
            intent = o.optString("intent"),
            subGoals = o.optJSONArray("subGoals").toStringList(),
            requiredSkills = o.optJSONArray("requiredSkills").toStringList(),
            priority = o.optInt("priority", 5),
            createdMs = o.optLong("createdMs"),
            deadlineMs = if (o.isNull("deadlineMs")) null else o.optLong("deadlineMs"),
            tags = o.optJSONArray("tags").toStringList()
        )

    private fun decodeGraph(o: JSONObject): TaskGraph {
        val tasks = o.optJSONArray("tasks")?.let { arr ->
            (0 until arr.length()).map { idx -> decodeTask(arr.getJSONObject(idx)) }
        } ?: emptyList()
        return TaskGraph(
            goalId = o.optString("goalId"),
            tasks = tasks,
            createdMs = o.optLong("createdMs")
        )
    }

    private fun decodeTask(o: JSONObject): TaskNode =
        TaskNode(
            id = o.optString("id"),
            goalId = o.optString("goalId"),
            description = o.optString("description"),
            skillId = if (o.isNull("skillId")) null else o.optString("skillId"),
            dependencies = o.optJSONArray("dependencies").toStringList(),
            estimatedMs = o.optLong("estimatedMs", 0L),
            status = TaskStatus.entries.firstOrNull { it.name == o.optString("status") }
                ?: TaskStatus.PENDING,
            result = if (o.isNull("result")) null else o.optString("result"),
            startedMs = if (o.isNull("startedMs")) null else o.optLong("startedMs"),
            completedMs = if (o.isNull("completedMs")) null else o.optLong("completedMs"),
            // Missing/unknown value decodes to null (generic) — old A5 snapshots
            // predating this field still load cleanly.
            failureKind = TaskFailureKind.entries.firstOrNull {
                it.name == o.optString("failureKind")
            }
        )

    private fun decodePlan(
        o: JSONObject,
        goal: com.newax.aegis.engine.intelligence.Goal,
        graph: TaskGraph
    ): PlanResult =
        PlanResult(
            goal = goal,
            graph = graph,
            feasible = o.optBoolean("feasible"),
            missingSkills = o.optJSONArray("missingSkills").toStringList(),
            missingCapabilities = o.optJSONArray("missingCapabilities").toStringList(),
            warnings = o.optJSONArray("warnings").toStringList()
        )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { idx -> getString(idx) }
    }
}

/**
 * Android store: [GoalPlanner.onChange] wires save(); bootstrap calls restore()
 * after the database is initialized. Writes are tiny and infrequent (per goal
 * mutation), so the existing runBlocking pattern for kv_store access applies.
 */
class DbGoalSnapshotStore(private val dao: KvStoreDao) {

    companion object {
        private const val KEY = "goals_snapshot_v1"
    }

    fun save(snapshots: List<GoalSnapshot>) {
        val json = GoalSnapshotCodec.encode(snapshots)
        runBlocking { dao.put(KvStoreEntity(KEY, json)) }
    }

    fun restore() {
        val json = runBlocking { dao.get(KEY) } ?: return
        val decoded = GoalSnapshotCodec.decode(json) ?: return // corrupt → drop, start empty
        GoalPlanner.restore(decoded)
    }
}
