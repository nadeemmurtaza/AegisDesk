package com.newax.aegis.engine.audit

import com.newax.aegis.db.dao.KvStoreDao
import com.newax.aegis.db.entity.KvStoreEntity
import com.newax.aegis.engine.intelligence.TaskStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** How a goal run ended. */
enum class RunOutcome { COMPLETED, FAILED }

/** One task's execution within a run — the audit's unit of detail. */
data class TaskRunRecord(
    val taskId: String,
    val description: String,
    val skillId: String?,
    /** The ExecutionRouter tier used (e.g. ANDROID_API), or null for a plain skill run. */
    val tier: String?,
    val status: TaskStatus,
    val result: String?,
    val startedMs: Long,
    val finishedMs: Long?
) {
    val durationMs: Long? get() = finishedMs?.minus(startedMs)
}

/**
 * One goal run: which goal, when, how it ended, and every task it touched.
 * Track A8 — the audit trail behind the Goals screen's "Recent runs" section
 * (rule 6: every consequential modification is auditable). Persisted to the
 * existing kv_store table via org.json, same pattern as goal snapshots (A5).
 */
data class ExecutionAuditEntry(
    val id: String,
    val goalId: String,
    val goalDescription: String,
    val outcome: RunOutcome,
    val startedMs: Long,
    val finishedMs: Long?,
    val tasks: List<TaskRunRecord>
) {
    val durationMs: Long? get() = finishedMs?.minus(startedMs)
}

/** Cap on persisted entries — a bounded ring of the most recent runs. */
internal const val MAX_AUDIT_ENTRIES = 25

/** Pure append with the ring cap — extracted so the bound is testable on the JVM. */
internal fun appendAudit(
    entries: List<ExecutionAuditEntry>,
    entry: ExecutionAuditEntry,
    maxSize: Int = MAX_AUDIT_ENTRIES
): List<ExecutionAuditEntry> = (entries + entry).takeLast(maxSize)

object ExecutionAuditCodec {

    private const val VERSION = 1
    private const val KEY_VERSION = "v"
    private const val KEY_RUNS = "runs"

    fun encode(entries: List<ExecutionAuditEntry>): String =
        JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_RUNS, JSONArray(entries.map { encodeEntry(it) }))
            .toString()

    /** Returns null when the stored JSON is corrupt or from an unknown format. */
    fun decode(json: String): List<ExecutionAuditEntry>? {
        return try {
            val root = JSONObject(json)
            if (root.optInt(KEY_VERSION, 0) != VERSION) null else {
                val arr = root.optJSONArray(KEY_RUNS) ?: return null
                (0 until arr.length()).map { idx -> decodeEntry(arr.getJSONObject(idx)) }
            }
        } catch (_: JSONException) {
            null
        }
    }

    private fun encodeEntry(e: ExecutionAuditEntry): JSONObject =
        JSONObject()
            .put("id", e.id)
            .put("goalId", e.goalId)
            .put("goalDescription", e.goalDescription)
            .put("outcome", e.outcome.name)
            .put("startedMs", e.startedMs)
            .put("finishedMs", e.finishedMs)
            .put("tasks", JSONArray(e.tasks.map { encodeTask(it) }))

    private fun encodeTask(t: TaskRunRecord): JSONObject =
        JSONObject()
            .put("taskId", t.taskId)
            .put("description", t.description)
            .put("skillId", t.skillId)
            .put("tier", t.tier)
            .put("status", t.status.name)
            .put("result", t.result)
            .put("startedMs", t.startedMs)
            .put("finishedMs", t.finishedMs)

    private fun decodeEntry(o: JSONObject): ExecutionAuditEntry {
        val tasks = o.optJSONArray("tasks")?.let { arr ->
            (0 until arr.length()).map { idx -> decodeTask(arr.getJSONObject(idx)) }
        } ?: emptyList()
        return ExecutionAuditEntry(
            id = o.optString("id"),
            goalId = o.optString("goalId"),
            goalDescription = o.optString("goalDescription"),
            outcome = RunOutcome.entries.firstOrNull { it.name == o.optString("outcome") }
                ?: RunOutcome.FAILED,
            startedMs = o.optLong("startedMs"),
            finishedMs = if (o.isNull("finishedMs")) null else o.optLong("finishedMs"),
            tasks = tasks
        )
    }

    private fun decodeTask(o: JSONObject): TaskRunRecord =
        TaskRunRecord(
            taskId = o.optString("taskId"),
            description = o.optString("description"),
            skillId = if (o.isNull("skillId")) null else o.optString("skillId"),
            tier = if (o.isNull("tier")) null else o.optString("tier"),
            status = TaskStatus.entries.firstOrNull { it.name == o.optString("status") }
                ?: TaskStatus.PENDING,
            result = if (o.isNull("result")) null else o.optString("result"),
            startedMs = o.optLong("startedMs"),
            finishedMs = if (o.isNull("finishedMs")) null else o.optLong("finishedMs")
        )
}

/** kv_store-backed persistence for the audit ring (no schema change, no migration). */
class ExecutionAuditStore(private val dao: KvStoreDao) {

    companion object {
        private const val KEY = "execution_audit_v1"
    }

    fun load(): List<ExecutionAuditEntry> {
        val json = runBlocking { dao.get(KEY) } ?: return emptyList()
        return ExecutionAuditCodec.decode(json) ?: emptyList() // corrupt → start empty, never crash
    }

    fun save(entries: List<ExecutionAuditEntry>) {
        val bounded = entries.takeLast(MAX_AUDIT_ENTRIES)
        runBlocking { dao.put(KvStoreEntity(KEY, ExecutionAuditCodec.encode(bounded))) }
    }
}

/**
 * Process-wide holder for the audit trail, wired at bootstrap (NewaxApplication).
 * The executor records each run; the Goals screen reads [recent] for "Recent runs".
 * Safe before [init] (tests, early bootstrap): record is a no-op and recent is empty.
 */
object ExecutionAuditHolder {

    private val lock = Any()

    @Volatile
    private var store: ExecutionAuditStore? = null

    private var entries: List<ExecutionAuditEntry> = emptyList()

    fun init(dao: KvStoreDao) {
        synchronized(lock) {
            if (store != null) return
            store = ExecutionAuditStore(dao)
            entries = store!!.load()
        }
    }

    /** Record a finished run. No-op when the holder isn't initialized (e.g. tests). */
    fun record(entry: ExecutionAuditEntry) {
        synchronized(lock) {
            val current = store ?: return
            entries = appendAudit(entries, entry)
            current.save(entries)
        }
    }

    /** Most recent runs first, bounded by [limit]. */
    fun recent(limit: Int): List<ExecutionAuditEntry> = synchronized(lock) {
        entries.takeLast(limit).reversed()
    }

    /** The full trail, oldest first (chronological append order) — for CSV exports. */
    fun all(): List<ExecutionAuditEntry> = synchronized(lock) { entries }
}
