package com.newax.aegis

import android.content.Context
import com.newax.aegis.authority.PolicyAuditRecord
import com.newax.aegis.authority.PolicyEngine
import com.newax.aegis.authority.SecureSettingsPolicyStore
import com.newax.aegis.engine.AndroidSecureSettings
import com.newax.aegis.engine.AutomationSettings
import com.newax.aegis.engine.AutomationToggle
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-level holder for the one [PolicyEngine] per process — the authority
 * spine's policy layer (ARCHITECTURE.md rule 3). Initialized once during
 * Application bootstrap ([AegisApplication.onCreate]).
 *
 * There is exactly one policy surface: the authority flow
 * ([MainViewModel.processAction]) evaluates actions through this instance, and
 * the Policy settings screen reads/writes the same instance's overrides and
 * denies. User overrides persist in the encrypted settings store
 * ([SecureSettingsPolicyStore]); the toggle gate reads the existing automation
 * toggles; every evaluation is appended to an in-memory audit trail surfaced by
 * [recentAudits] (RULE 8: consequential modifications are auditable).
 */
object PolicyHolder {

    private val lock = Any()

    @Volatile
    private var engine: PolicyEngine? = null

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
                auditSink = { record -> audits.add(record) },
            )
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
}
