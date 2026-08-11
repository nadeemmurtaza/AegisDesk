package com.newax.aegis.desktop

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.authority.PolicyEngine
import com.newax.aegis.authority.PolicyEvaluation
import com.newax.aegis.authority.PolicyMode
import com.newax.aegis.authority.PolicyStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList

/** Why a goal task failed — the desktop mirror of Android's `TaskFailureKind`. */
enum class TaskFailureKind { POLICY, CAPABILITY }

/**
 * File-backed [PolicyStore] — the user-controllable half of the desktop
 * authority spine: per-action-class mode overrides and hard denies, persisted
 * under `~/.aegis/policy-settings.json` (the desktop twin of Android's
 * encrypted settings store; same JSON + atomic-write convention as
 * [FileGoalsStore]). A corrupt, missing, or unsupported-version file starts
 * from defaults — the conservative reading (R9) — never a crash.
 */
class FilePolicyStore(private val file: Path) : PolicyStore {

    constructor() : this(defaultFile())

    companion object {
        const val SCHEMA_VERSION = 1

        fun defaultFile(): Path =
            Paths.get(System.getProperty("user.home") ?: ".", ".aegis", "policy-settings.json")
    }

    private val overrides = mutableMapOf<String, PolicyMode>()
    private val denied = mutableSetOf<String>()

    init {
        load()
    }

    private fun load() {
        if (!Files.isRegularFile(file)) return
        try {
            val root = JSONObject(Files.readString(file))
            if (root.optInt("version", -1) != SCHEMA_VERSION) return
            root.optJSONObject("modes")?.keys()?.forEach { key ->
                PolicyMode.entries.firstOrNull { it.name == root.optJSONObject("modes").optString(key) }
                    ?.let { overrides[key] = it }
            }
            root.optJSONArray("denied")?.let { arr ->
                (0 until arr.length()).forEach { idx -> denied.add(arr.getString(idx)) }
            }
        } catch (_: IOException) {
            // unreadable file → defaults (never a crash)
        } catch (_: JSONException) {
            // corrupt file → defaults (never a crash)
        }
    }

    private fun persist() {
        try {
            file.parent?.let { Files.createDirectories(it) }
            val modes = JSONObject()
            overrides.forEach { (key, mode) -> modes.put(key, mode.name) }
            val root = JSONObject()
                .put("version", SCHEMA_VERSION)
                .put("modes", modes)
                .put("denied", JSONArray(denied.toList()))
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.write(tmp, root.toString().toByteArray(Charsets.UTF_8))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            // Best-effort persistence (named failure mode): a failed save keeps
            // the in-memory overrides authoritative for this session.
            println("[policy] settings save failed ($file): ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun modeOverride(actionClass: String): PolicyMode? = overrides[actionClass]

    override fun setModeOverride(actionClass: String, mode: PolicyMode) {
        overrides[actionClass] = mode
        persist()
    }

    override fun clearModeOverride(actionClass: String) {
        overrides.remove(actionClass)
        persist()
    }

    override fun isDenied(actionClass: String): Boolean = actionClass in denied

    override fun setDenied(actionClass: String, denied: Boolean) {
        if (denied) this.denied.add(actionClass) else this.denied.remove(actionClass)
        persist()
    }
}

/** org.json codec for the policy-decision audit trail — versioned, corrupt-safe. */
object PolicyAuditCodec {

    private const val VERSION = 1
    private const val KEY_VERSION = "v"
    private const val KEY_RECORDS = "records"

    fun encode(records: List<PolicyAuditRecord>): String =
        JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_RECORDS, JSONArray(records.map { encodeRecord(it) }))
            .toString()

    /** Returns null when the stored JSON is corrupt or from an unknown format. */
    fun decode(json: String): List<PolicyAuditRecord>? {
        return try {
            val root = JSONObject(json)
            if (root.optInt(KEY_VERSION, 0) != VERSION) null else {
                val arr = root.optJSONArray(KEY_RECORDS) ?: return null
                (0 until arr.length()).map { idx -> decodeRecord(arr.getJSONObject(idx)) }
            }
        } catch (_: JSONException) {
            null
        }
    }

    private fun encodeRecord(r: PolicyAuditRecord): JSONObject =
        JSONObject()
            .put("actionClass", r.actionClass)
            .put("actionSummary", r.actionSummary)
            .put("origin", r.origin.name)
            .put("risk", r.risk.name)
            .put("mode", r.mode.name)
            .put("decision", r.decision.name)
            .put("reason", r.reason)
            .put("auditedAtMs", r.auditedAtMs)

    private fun decodeRecord(o: JSONObject): PolicyAuditRecord =
        PolicyAuditRecord(
            actionClass = o.optString("actionClass"),
            actionSummary = o.optString("actionSummary"),
            origin = ActionOrigin.entries.firstOrNull { it.name == o.optString("origin") }
                ?: ActionOrigin.USER,
            risk = RiskLevel.entries.firstOrNull { it.name == o.optString("risk") }
                ?: RiskLevel.MEDIUM,
            mode = PolicyMode.entries.firstOrNull { it.name == o.optString("mode") }
                ?: PolicyMode.APPROVAL,
            decision = PolicyDecision.entries.firstOrNull { it.name == o.optString("decision") }
                ?: PolicyDecision.REQUIRE_APPROVAL,
            reason = o.optString("reason"),
            auditedAtMs = o.optLong("auditedAtMs")
        )
}

/** Cap on persisted records — a bounded ring of the most recent decisions. */
const val MAX_POLICY_AUDIT_RECORDS = 200

/** Pure append with the ring cap — extracted so the bound is testable on the JVM. */
fun appendPolicyAudit(
    records: List<PolicyAuditRecord>,
    record: PolicyAuditRecord,
    maxSize: Int = MAX_POLICY_AUDIT_RECORDS
): List<PolicyAuditRecord> = (records + record).takeLast(maxSize)

/**
 * File-backed policy-decision audit trail under `~/.aegis/policy-audit.json` —
 * the desktop twin of Android's kv_store-backed store. Bounded ring; corrupt or
 * missing file → honest empty start, never a crash.
 */
class FilePolicyAuditStore(private val file: Path) {

    constructor() : this(defaultFile())

    companion object {
        fun defaultFile(): Path =
            Paths.get(System.getProperty("user.home") ?: ".", ".aegis", "policy-audit.json")
    }

    fun load(): List<PolicyAuditRecord> {
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            PolicyAuditCodec.decode(Files.readString(file)) ?: emptyList()
        } catch (_: IOException) {
            emptyList()
        }
    }

    fun save(records: List<PolicyAuditRecord>) {
        try {
            file.parent?.let { Files.createDirectories(it) }
            val bounded = records.takeLast(MAX_POLICY_AUDIT_RECORDS)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.write(tmp, PolicyAuditCodec.encode(bounded).toByteArray(Charsets.UTF_8))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            println("[policy] audit save failed ($file): ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** One action class's share of the policy history, for the summary view. */
data class ActionClassStat(
    val actionClass: String,
    val total: Int,
    val autoExecuted: Int,
    /** REQUIRE_APPROVAL + REQUIRE_STRONG — the decisions that prompted a human. */
    val needsHuman: Int,
    val denied: Int,
)

/**
 * Per-action-class totals for the policy-decision history — which actions need
 * the most approvals. "Needs human" counts REQUIRE_APPROVAL + REQUIRE_STRONG
 * (DENY blocks outright and is counted separately, never a prompt). Sorted by
 * needsHuman desc, then total desc, then class name (deterministic); an empty
 * history yields an empty list.
 */
fun actionClassBreakdown(records: List<PolicyAuditRecord>): List<ActionClassStat> =
    records.groupBy { it.actionClass }
        .map { (actionClass, recs) ->
            ActionClassStat(
                actionClass = actionClass,
                total = recs.size,
                autoExecuted = recs.count { it.decision == PolicyDecision.AUTO_EXECUTE },
                needsHuman = recs.count {
                    it.decision == PolicyDecision.REQUIRE_APPROVAL ||
                        it.decision == PolicyDecision.REQUIRE_STRONG
                },
                denied = recs.count { it.decision == PolicyDecision.DENY }
            )
        }
        .sortedWith(
            compareByDescending<ActionClassStat> { it.needsHuman }
                .thenByDescending { it.total }
                .thenBy { it.actionClass }
        )

/**
 * Process-wide holder for the desktop policy layer — the twin of Android's
 * `PolicyHolder`. One [PolicyEngine] per process: user overrides/denies persist
 * to [FilePolicyStore], every evaluation is appended to the [FilePolicyAuditStore]
 * ring (RULE 8), and the Goals executor gates privileged tasks through
 * [evaluateOrNull]. Wired at bootstrap in Main (window + CLI).
 */
object DesktopPolicyHolder {

    private val lock = Any()

    @Volatile
    private var engine: PolicyEngine? = null

    @Volatile
    private var auditStore: FilePolicyAuditStore? = null

    private val audits = CopyOnWriteArrayList<PolicyAuditRecord>()

    /**
     * Builds the one engine (first call wins). [resetForTest] clears it so tests
     * can re-init against temp files; production entry points call this exactly
     * once during bootstrap.
     */
    fun init(
        policyFile: Path = FilePolicyStore.defaultFile(),
        auditFile: Path = FilePolicyAuditStore.defaultFile(),
    ) {
        synchronized(lock) {
            if (engine != null) return
            auditStore = FilePolicyAuditStore(auditFile)
            engine = PolicyEngine(
                store = FilePolicyStore(policyFile),
                // Desktop has no automation toggles: CONFIGURABLE conservatively
                // reads as "toggle off" → approval (Android's pre-init reading).
                toggleKeyForAction = { null },
                isToggleEnabled = { false },
                auditSink = { record -> recordAudit(record) },
            )
        }
    }

    /** Test seam — clears the holder so a test can re-init against temp files. */
    fun resetForTest() {
        synchronized(lock) {
            engine = null
            auditStore = null
            audits.clear()
        }
    }

    /** The one engine; requires [init] during bootstrap. */
    fun engine(): PolicyEngine = requireNotNull(engine) {
        "DesktopPolicyHolder not initialized — call init() during bootstrap"
    }

    /** The one engine, or null before init. */
    fun engineOrNull(): PolicyEngine? = engine

    /**
     * Evaluates through the one engine, or null when the holder is not
     * initialized — the executor gate uses this so execution degrades exactly
     * as before the authority slice landed (tests, pre-bootstrap).
     */
    fun evaluateOrNull(action: ProposedAction, origin: ActionOrigin): PolicyEvaluation? =
        engine?.evaluate(action, origin)

    /** The full policy-decision history, oldest first (persisted across sessions). */
    fun auditHistory(): List<PolicyAuditRecord> = synchronized(lock) { audits.toList() }

    /** The most recent [limit] decisions, oldest first. */
    fun recentAudits(limit: Int): List<PolicyAuditRecord> = synchronized(lock) { audits.takeLast(limit) }

    /** Clears the recorded history (memory and persisted ring). */
    fun clearAuditHistory() {
        synchronized(lock) {
            audits.clear()
            auditStore?.save(emptyList())
        }
    }

    /** Engine audit sink: always record in memory; write through once persisted. */
    private fun recordAudit(record: PolicyAuditRecord) {
        synchronized(lock) {
            audits.add(record)
            val store = auditStore
            if (store != null) {
                store.save(audits.takeLast(MAX_POLICY_AUDIT_RECORDS))
            } else {
                while (audits.size > MAX_POLICY_AUDIT_RECORDS) {
                    audits.removeAt(0)
                }
            }
        }
    }
}
