package com.newax.aegis.authority

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.MACHINE_AUTO_EXECUTE_CEILING
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.assistant.confirmationWarning
import com.newax.aegis.assistant.requiresBiometric
import com.newax.aegis.assistant.riskOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Property tests over the policy engine's decision table (Track 2.3).
 *
 * **Scope.** These are the engine's *own* invariants, derived from the
 * ARCHITECTURE.md decision table (rule 3 corollary + the T2.2 concept registry).
 * The four invariants `ENGINEERING.md` §B7 names are a different, broader list
 * about the spine end to end — two of them are statements about
 * [AuthorityManager], not about this class — and they live in
 * [AuthoritySpinePropertyTest]. Neither file subsumes the other; read them as a
 * pair.
 *
 * (An earlier revision of this header claimed §B7 "is not present in this
 * snapshot". It is — `docs/ENGINEERING.md`, §B7, "Testing strategy". The
 * invariants below were never the §B7 four.)
 *
 *  - **I1 — deny is absolute.** A user deny for the action class wins over every
 *    mode and origin: the decision is always DENY.
 *  - **I2 — machine ceiling.** An action at or above `MACHINE_AUTO_EXECUTE_CEILING`
 *    never auto-executes from a machine origin (BACKGROUND/AGENT), regardless of
 *    mode or toggle. A silent downgrade here is the exact mis-mapping T2.2 was
 *    created to kill.
 *  - **I3 — gate honesty.** APPROVAL always yields REQUIRE_APPROVAL and
 *    STRONG_CONFIRMATION always yields REQUIRE_STRONG — no mode silently
 *    auto-executes.
 *  - **I4 — autonomy soundness.** `allowsAutonomousExecution` is true exactly
 *    when the decision is AUTO_EXECUTE, and every AUTO_EXECUTE is lawful:
 *    user origin or below the machine ceiling.
 *
 * Plus the two cross-cutting properties every evaluation carries: a complete
 * audit record (I5) and a default gate mapping that is total and
 * order-preserving (I6).
 *
 * Exhaustive (not sampled): every ProposedAction variant × every origin × every
 * mode × both toggle states, plus every variant × origin × mode with a hard
 * deny. That is ~1,000 evaluations — fast, deterministic, and no new
 * dependencies (R5: prefer what is present).
 */
class PolicyEnginePropertyTest {

    /** Every ProposedAction variant with representative arguments. */
    private fun allActions(): List<ProposedAction> = ProposedActionCorpus.all()

    /**
     * The corpus is only "exhaustive" while it keeps up with the sealed
     * interface, and nothing in the language enforces that — Kotlin/Native has
     * no reflective enumeration of a sealed hierarchy's implementations. Pinning
     * the count is the enforcement: adding a `ProposedAction` variant without
     * adding it to [ProposedActionCorpus] fails here, with a message saying so,
     * instead of silently shrinking every exhaustive test in this package.
     */
    @Test
    fun `the corpus covers every ProposedAction variant`() {
        assertEquals(
            EXPECTED_VARIANT_COUNT,
            ProposedActionCorpus.distinctClassNames().size,
            "ProposedAction gained or lost a variant. Add it to ProposedActionCorpus " +
                "(and give it a risk classification in riskOf) before updating this count — " +
                "every exhaustive test in this package iterates that list.",
        )
    }

    private fun engineWith(toggleEnabled: Boolean): PolicyEngine = PolicyEngine(
        toggleKeyForAction = { "t" },
        isToggleEnabled = { toggleEnabled },
    )

    private fun classNameOf(action: ProposedAction): String =
        action::class.simpleName ?: error("every ProposedAction has a simple name")

    // ── I2 + I3 + I4 + I5: every action × origin × mode × toggle ──────────────

    @Test
    fun `the four invariants hold for every action, origin, mode, and toggle`() {
        for (action in allActions()) {
            val risk = riskOf(action)
            for (origin in ActionOrigin.entries) {
                for (mode in PolicyMode.entries) {
                    for (toggle in listOf(false, true)) {
                        val engine = engineWith(toggle)
                        engine.setModeOverride(classNameOf(action), mode)
                        val evaluation = engine.evaluate(action, origin)
                        val record = evaluation.audit

                        // I5 — audit completeness: the record carries exactly the
                        // evaluated action, origin, risk, mode, and decision.
                        assertEquals(classNameOf(action), record.actionClass)
                        assertEquals(origin, record.origin)
                        assertEquals(risk, record.risk)
                        assertEquals(mode, record.mode)
                        assertEquals(evaluation.decision, record.decision)
                        assertTrue(record.reason.isNotBlank(), "reason must explain the decision")
                        assertTrue(record.auditedAtMs > 0, "audit must be timestamped")

                        // I2 — machine ceiling: at/above the ceiling a machine origin
                        // never auto-executes, no matter the mode or toggle.
                        if (origin != ActionOrigin.USER && risk >= MACHINE_AUTO_EXECUTE_CEILING) {
                            assertNotEquals(
                                PolicyDecision.AUTO_EXECUTE,
                                evaluation.decision,
                                "machine origin ($origin) auto-executed ${classNameOf(action)} at risk $risk",
                            )
                        }

                        // I3 — gate honesty: approval/strong modes always produce the
                        // human gate, never a silent auto-execute.
                        if (mode == PolicyMode.APPROVAL) {
                            assertEquals(PolicyDecision.REQUIRE_APPROVAL, evaluation.decision)
                        }
                        if (mode == PolicyMode.STRONG_CONFIRMATION) {
                            assertEquals(PolicyDecision.REQUIRE_STRONG, evaluation.decision)
                        }

                        // I4 — autonomy soundness: allowsAutonomousExecution ⇔
                        // AUTO_EXECUTE, and every auto-execute is lawful (user origin
                        // or below the ceiling — no silent downgrade path).
                        assertEquals(
                            evaluation.decision == PolicyDecision.AUTO_EXECUTE,
                            evaluation.decision.allowsAutonomousExecution,
                        )
                        if (evaluation.decision == PolicyDecision.AUTO_EXECUTE) {
                            assertTrue(
                                origin == ActionOrigin.USER || risk < MACHINE_AUTO_EXECUTE_CEILING,
                                "AUTO_EXECUTE at risk $risk from $origin violates the ceiling",
                            )
                        }
                    }
                }
            }
        }
    }

    // ── I1: deny is absolute ───────────────────────────────────────────────────

    @Test
    fun `a hard deny refuses the action for every origin and mode`() {
        for (action in allActions()) {
            for (origin in ActionOrigin.entries) {
                for (mode in PolicyMode.entries) {
                    val engine = engineWith(toggleEnabled = true)
                    engine.setModeOverride(classNameOf(action), mode)
                    engine.setDenied(classNameOf(action), true)
                    val evaluation = engine.evaluate(action, origin)
                    assertEquals(
                        PolicyDecision.DENY,
                        evaluation.decision,
                        "denied ${classNameOf(action)} from $origin in mode $mode was not refused",
                    )
                    assertTrue(evaluation.reason.contains("denies"))
                    assertTrue(!evaluation.decision.allowsAutonomousExecution)
                }
            }
        }
    }

    // ── I6: the default gate mapping is total and order-preserving ─────────────

    @Test
    fun `default gate mapping covers every risk level in severity order`() {
        val risks = RiskLevel.entries
        val gates = risks.map(PolicyEngine::defaultModeFor)
        assertEquals(4, risks.size)
        // Total: every risk maps to a defined gate (a missing branch would fail
        // compilation of the `when` — this asserts the observable table).
        assertEquals(listOf(PolicyMode.AUTO, PolicyMode.CONFIGURABLE, PolicyMode.APPROVAL, PolicyMode.STRONG_CONFIRMATION), gates)
        // Order-preserving: a riskier action never gets a weaker default gate.
        assertEquals(risks.sortedBy { it.ordinal }, risks)
        assertEquals(gates.sortedBy { it.ordinal }, gates)
    }

    @Test
    fun `effective mode with no override is exactly the default gate`() {
        val engine = PolicyEngine()
        for (action in allActions()) {
            assertEquals(
                PolicyEngine.defaultModeFor(riskOf(action)),
                engine.effectiveMode(action),
                "effectiveMode drifted from defaultModeFor for ${classNameOf(action)}",
            )
        }
    }

    // ── The risk → UI chain stays in lockstep with the table ───────────────────

    @Test
    fun `biometric and warning surfaces match the classification table`() {
        for (action in allActions()) {
            val risk = riskOf(action)
            assertEquals(risk >= RiskLevel.HIGH, requiresBiometric(action), "requiresBiometric mismatch for ${classNameOf(action)}")
            assertEquals(
                risk >= RiskLevel.HIGH,
                action.confirmationWarning != null,
                "confirmationWarning mismatch for ${classNameOf(action)}",
            )
        }
    }

    private companion object {
        /** Sealed subtypes of `ProposedAction` in `assistant/Models.kt`. */
        const val EXPECTED_VARIANT_COUNT = 41
    }
}
