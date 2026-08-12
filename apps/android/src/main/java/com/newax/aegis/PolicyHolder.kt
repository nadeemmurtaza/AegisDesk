package com.newax.aegis

import android.content.Context
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyEngine
import com.newax.aegis.authority.SecureSettingsPolicyStore
import com.newax.aegis.db.dao.KvStoreDao
import com.newax.aegis.engine.AndroidSecureSettings
import com.newax.aegis.engine.AutomationSettings
import com.newax.aegis.engine.AutomationToggle
import com.newax.aegis.engine.audit.MAX_POLICY_AUDIT_RECORDS
import com.newax.aegis.engine.audit.PolicyAuditStore
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-level holder for the one [PolicyEngine] per process — the authority
 * spine's policy layer (ARCHITECTURE.md rule 3). Initialized once during
 * Application bootstrap ([NewaxApplication.onCreate]).
 *
 * There is exactly one policy surface: the authority flow
 * ([MainViewModel.processAction]) evaluates actions through this instance, and
 * the Policy settings screen reads/writes the same instance's overrides and
 * denies. User overrides persist in the encrypted settings store
 * ([SecureSettingsPolicyStore]); the toggle gate reads the existing automation
 * toggles; every evaluation is appended to an audit trail surfaced by
 * [recentAudits]/[auditHistory] (RULE 8: consequential modifications are
 * auditable).
 *
 * The audit trail persists across sessions: [initAuditPersistence] (wired after
 * the database initializes) loads the previous decisions from kv_store, and the
 * engine's audit sink writes through to the store on every evaluation. Records
 * evaluated before the database is ready are merged in on wire-up, never lost.
 */
object PolicyHolder {

    private val lock = Any()

    @Volatile
    private var engine: PolicyEngine? = null

    @Volatile
    private var auditStore: PolicyAuditStore? = null

    private val audits = CopyOnWriteArrayList<PolicyAuditRecord>()

    /** Builds the one engine. Safe to call repeatedly; the first call wins. */
    fun init(context: Context) {
        synchronized(lock) {
            if (engine != null) return
            engine = PolicyEngine(
                store = SecureSettingsPolicyStore(AndroidSecureSettings(context)),
                toggleKeyForAction = { action -> AutomationSettings.toggleForAction(action)?.key },
                isToggleEnabled = { key ->
                    AutomationToggle.entries.firstOrNull { it.key == key }
                        ?.let { AutomationSettings.isEnabled(it) } == true
                },
                auditSink = { record -> recordAudit(record) },
            )
        }
    }

    /**
     * Wires the persistent audit trail. Called after the database initializes
     * (NewaxApplication bootstrap); safe to call repeatedly. Loads decisions
     * from previous sessions and merges any records evaluated before this call
     * (early bootstrap) so nothing is lost.
     */
    fun initAuditPersistence(dao: KvStoreDao) {
        synchronized(lock) {
            if (auditStore != null) return
            auditStore = PolicyAuditStore(dao)
            val persisted = auditStore!!.load()
            if (audits.isNotEmpty()) {
                val merged = (persisted + audits).takeLast(MAX_POLICY_AUDIT_RECORDS)
                audits.clear()
                audits.addAll(merged)
                auditStore!!.save(merged)
            }
        }
    }

    /** The one engine; requires [init] during bootstrap. */
    fun engine(): PolicyEngine = requireNotNull(engine) { "PolicyHolder not initialized — call init(context) during bootstrap" }

    /**
     * The one engine, or null before bootstrap [init]. For read-only pre-flight
     * (e.g. the planner's policy warnings) where a missing engine must degrade
     * gracefully instead of crashing.
     */
    fun engineOrNull(): PolicyEngine? = engine

    /** The most recent [limit] audit records, oldest first. */
    fun recentAudits(limit: Int): List<PolicyAuditRecord> = audits.takeLast(limit)

    /**
     * The full policy-decision history, oldest first — every recorded evaluation
     * across sessions (persisted via kv_store; empty before [initAuditPersistence]).
     */
    fun auditHistory(): List<PolicyAuditRecord> = synchronized(lock) { audits.toList() }

    /** Clears the recorded policy-decision history (memory and persisted ring). */
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
                // Pre-DB-init evaluations: keep the memory ring bounded until
                // initAuditPersistence wires the store and merges these in.
                while (audits.size > MAX_POLICY_AUDIT_RECORDS) {
                    audits.removeAt(0)
                }
            }
        }
    }
}
