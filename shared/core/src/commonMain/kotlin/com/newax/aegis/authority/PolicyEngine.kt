package com.newax.aegis.authority

import com.newax.aegis.currentTimeMillis

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.assistant.riskLevel
import com.newax.aegis.engine.AutomationSettings

/**
 * Policy modes — how much the user has authorized Newax to do on its own.
 * The mode is the *policy* answer ("should Newax do this automatically"),
 * orthogonal to the *permission* answer ("can the OS/account do this").
 * ARCHITECTURE.md corollary: PrivilegeLevel maps to these modes
 * (READ_ONLY→AUTO, STANDARD→CONFIGURABLE, HIGH_IMPACT_SYSTEM→APPROVAL,
 * CRITICAL→STRONG_CONFIRMATION) and the mapping is user-controllable.
 */
enum class PolicyMode {
    /** Execute without prompting (subject to the background-origin ceiling). */
    AUTO,

    /** Execute only while the user's automation toggle for this action is on; otherwise approval. */
    CONFIGURABLE,

    /** Always show an approval prompt — never silently execute, regardless of toggles. */
    APPROVAL,

    /** Approval prompt plus strong confirmation (biometric) — irreversible/outward-facing. */
    STRONG_CONFIRMATION,
}

/** The policy layer's decision for one proposed action. */
enum class PolicyDecision {
    AUTO_EXECUTE,
    REQUIRE_APPROVAL,
    REQUIRE_STRONG,
    DENY;

    /**
     * True only for [AUTO_EXECUTE] — every other decision needs a human before the
     * action runs. Rule 10 (PLAN is never EXECUTE): an executor that receives a
     * non-AUTO decision must refuse the action, not run it after a plan said so.
     */
    val allowsAutonomousExecution: Boolean get() = this == AUTO_EXECUTE
}

/**
 * One policy evaluation, persisted/streamed for audit (ARCHITECTURE.md RULE 8:
 * every consequential modification is auditable — who/what requested it and the
 * policy decision).
 */
data class PolicyAuditRecord(
    val actionClass: String,
    val actionSummary: String,
    val origin: ActionOrigin,
    val risk: RiskLevel,
    val mode: PolicyMode,
    val decision: PolicyDecision,
    val reason: String,
    val auditedAtMs: Long,
)

/** The evaluation of one action: the action itself plus its audit record. */
data class PolicyEvaluation(
    val action: ProposedAction,
    val audit: PolicyAuditRecord,
) {
    val mode: PolicyMode get() = audit.mode
    val decision: PolicyDecision get() = audit.decision
    val reason: String get() = audit.reason
}

/**
 * Persistence seam for the user-controllable part of the policy: per-action-class
 * mode overrides and hard denies. Platform-free; Android backs it with
 * SecureSettings in the wiring slice, tests use [InMemoryPolicyStore].
 */
interface PolicyStore {
    /** The user's mode override for an action class, or null for the default mapping. */
    fun modeOverride(actionClass: String): PolicyMode?

    fun setModeOverride(actionClass: String, mode: PolicyMode)

    fun clearModeOverride(actionClass: String)

    /** True when the user has hard-blocked an action class (deny beats every mode). */
    fun isDenied(actionClass: String): Boolean

    fun setDenied(actionClass: String, denied: Boolean)
}

/** Default [PolicyStore]: in-memory overrides/denies. */
class InMemoryPolicyStore : PolicyStore {
    private val overrides = mutableMapOf<String, PolicyMode>()
    private val denied = mutableSetOf<String>()

    override fun modeOverride(actionClass: String): PolicyMode? = overrides[actionClass]

    override fun setModeOverride(actionClass: String, mode: PolicyMode) {
        overrides[actionClass] = mode
    }

    override fun clearModeOverride(actionClass: String) {
        overrides.remove(actionClass)
    }

    override fun isDenied(actionClass: String): Boolean = actionClass in denied

    override fun setDenied(actionClass: String, denied: Boolean) {
        if (denied) this.denied.add(actionClass) else this.denied.remove(actionClass)
    }

    fun overrides(): Map<String, PolicyMode> = overrides.toMap()
}

/**
 * The authority spine's policy layer (ARCHITECTURE.md rule 3 — "AuthorityManager
 * today; a richer PolicyEngine as it evolves"). Given a proposed action and its
 * origin, it resolves the effective [PolicyMode] (user override → default
 * risk mapping), applies the policy decision table, and emits an audit record.
 *
 * Decision table (conservative at every edge — R9):
 *  - A user deny for the action class wins over every mode → DENY.
 *  - AUTO executes only if the action is below the machine ceiling when the
 *    origin is not USER (background text or an autonomous agent never carries
 *    the authority of the user saying it — mirror of
 *    `MACHINE_AUTO_EXECUTE_CEILING`).
 *  - CONFIGURABLE executes while the governing automation toggle is on; with no
 *    toggle mapped, the conservative reading is "toggle off" → approval.
 *  - APPROVAL and STRONG_CONFIRMATION never auto-execute, even with a toggle on
 *    (the toggle records what the user is willing to have done on their
 *    instruction; the mode says a human still confirms this class of action).
 *
 * Default mapping mirrors the PrivilegeLevel corollary:
 * LOW→AUTO, MEDIUM→CONFIGURABLE, HIGH→APPROVAL, CRITICAL→STRONG_CONFIRMATION.
 */
class PolicyEngine(
    private val store: PolicyStore = InMemoryPolicyStore(),
    private val toggleKeyForAction: (ProposedAction) -> String? = { action ->
        AutomationSettings.toggleForAction(action)?.key
    },
    private val isToggleEnabled: (String) -> Boolean = { false },
    private val auditSink: (PolicyAuditRecord) -> Unit = {},
) {

    /** Actions at/above this risk never auto-execute when the origin is a machine (background text or an agent). */
    private val machineCeiling = RiskLevel.HIGH

    fun evaluate(action: ProposedAction, origin: ActionOrigin): PolicyEvaluation {
        val actionClass = action::class.simpleName ?: "UnknownAction"
        val risk = action.riskLevel
        val mode = store.modeOverride(actionClass) ?: defaultModeFor(risk)
        val (decision, reason) = when {
            store.isDenied(actionClass) ->
                PolicyDecision.DENY to "user policy denies '$actionClass'"
            else -> decide(action, origin, mode)
        }
        val record = PolicyAuditRecord(
            actionClass = actionClass,
            actionSummary = action.summary,
            origin = origin,
            risk = risk,
            mode = mode,
            decision = decision,
            reason = reason,
            auditedAtMs = currentTimeMillis(),
        )
        auditSink(record)
        return PolicyEvaluation(action = action, audit = record)
    }

    private fun decide(action: ProposedAction, origin: ActionOrigin, mode: PolicyMode): Pair<PolicyDecision, String> {
        val risk = action.riskLevel
        val machineBlocked = origin != ActionOrigin.USER && risk >= machineCeiling
        return when (mode) {
            PolicyMode.AUTO -> if (machineBlocked) {
                PolicyDecision.REQUIRE_APPROVAL to
                    "mode AUTO but machine origin ($origin) at risk $risk never auto-executes"
            } else {
                PolicyDecision.AUTO_EXECUTE to "mode AUTO at risk $risk"
            }

            PolicyMode.CONFIGURABLE -> when {
                machineBlocked -> PolicyDecision.REQUIRE_APPROVAL to
                    "mode CONFIGURABLE but machine origin ($origin) at risk $risk never auto-executes"
                else -> {
                    val toggleKey = toggleKeyForAction(action)
                    if (toggleKey != null && isToggleEnabled(toggleKey)) {
                        PolicyDecision.AUTO_EXECUTE to "mode CONFIGURABLE and toggle '$toggleKey' is on"
                    } else {
                        PolicyDecision.REQUIRE_APPROVAL to
                            if (toggleKey == null) "mode CONFIGURABLE but no automation toggle maps this action (conservative: approval)"
                            else "mode CONFIGURABLE and toggle '$toggleKey' is off"
                    }
                }
            }

            PolicyMode.APPROVAL -> PolicyDecision.REQUIRE_APPROVAL to "mode APPROVAL at risk $risk"

            PolicyMode.STRONG_CONFIRMATION -> PolicyDecision.REQUIRE_STRONG to
                "mode STRONG_CONFIRMATION at risk $risk"
        }
    }

    /** The effective mode for an action: user override for its class, else the risk default. */
    fun effectiveMode(action: ProposedAction): PolicyMode =
        store.modeOverride(action::class.simpleName ?: "UnknownAction")
            ?: defaultModeFor(action.riskLevel)

    /** True when the user has pinned an override for the action class. */
    fun hasModeOverride(actionClass: String): Boolean = store.modeOverride(actionClass) != null

    /** The user's pinned override for the action class, or null when unset. */
    fun modeOverride(actionClass: String): PolicyMode? = store.modeOverride(actionClass)

    fun setModeOverride(actionClass: String, mode: PolicyMode) = store.setModeOverride(actionClass, mode)

    fun clearModeOverride(actionClass: String) = store.clearModeOverride(actionClass)

    fun isDenied(actionClass: String): Boolean = store.isDenied(actionClass)

    fun setDenied(actionClass: String, denied: Boolean) = store.setDenied(actionClass, denied)

    companion object {
        /** Default mapping — the ARCHITECTURE.md corollary, keyed by risk level. */
        fun defaultModeFor(risk: RiskLevel): PolicyMode = when (risk) {
            RiskLevel.LOW -> PolicyMode.AUTO
            RiskLevel.MEDIUM -> PolicyMode.CONFIGURABLE
            RiskLevel.HIGH -> PolicyMode.APPROVAL
            RiskLevel.CRITICAL -> PolicyMode.STRONG_CONFIRMATION
        }
    }
}
