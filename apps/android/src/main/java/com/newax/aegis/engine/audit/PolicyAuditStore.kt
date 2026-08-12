package com.newax.aegis.engine.audit

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyDecision
import com.newax.aegis.authority.PolicyMode
import com.newax.aegis.db.dao.KvStoreDao
import com.newax.aegis.db.entity.KvStoreEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * kv_store-backed persistence for the policy-decision audit trail — the same
 * org.json + kv_store pattern as the execution audit (Track A8) and goal
 * snapshots (Track A5): no schema change, no migration. Decisions survive
 * restarts so the user can review every approval across sessions.
 */
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
            // Unknown/missing enum values fall back to the conservative reading
            // (USER origin, MEDIUM risk, APPROVAL mode/decision) — never crash.
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
internal const val MAX_POLICY_AUDIT_RECORDS = 200

/** Pure append with the ring cap — extracted so the bound is testable on the JVM. */
internal fun appendPolicyAudit(
    records: List<PolicyAuditRecord>,
    record: PolicyAuditRecord,
    maxSize: Int = MAX_POLICY_AUDIT_RECORDS
): List<PolicyAuditRecord> = (records + record).takeLast(maxSize)

/** kv_store-backed persistence for the policy-decision audit ring. */
class PolicyAuditStore(private val dao: KvStoreDao) {

    companion object {
        private const val KEY = "policy_audit_v1"
    }

    fun load(): List<PolicyAuditRecord> {
        val json = runBlocking { dao.get(KEY) } ?: return emptyList()
        return PolicyAuditCodec.decode(json) ?: emptyList() // corrupt → start empty, never crash
    }

    fun save(records: List<PolicyAuditRecord>) {
        val bounded = records.takeLast(MAX_POLICY_AUDIT_RECORDS)
        runBlocking { dao.put(KvStoreEntity(KEY, PolicyAuditCodec.encode(bounded))) }
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
 * history yields an empty list (no divide-by-zero, R9).
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
