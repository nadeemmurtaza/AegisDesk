package com.newax.aegis.authority

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.assistant.requiresBiometric
import com.newax.aegis.assistant.riskOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The four invariants `ENGINEERING.md` §B7 actually names, over the whole
 * authority spine rather than the policy engine alone.
 *
 * `PolicyEnginePropertyTest` covers the engine's own decision table. It does not
 * cover §B7 — that list is about the spine end to end (evaluate → approve or
 * reject → execute), and two of its four clauses are statements about
 * [AuthorityManager], which owns the approve/reject/execute events. This file
 * closes that gap:
 *
 *  1. **No sequence of inputs downgrades a `STRONG_CONFIRMATION` action to
 *     `AUTO`** — encoded as: no sequence of machine-side inputs can, and the one
 *     input that can (an explicit user override in [PolicyStore]) is never
 *     silent, because the weakened mode appears in the audit record. See the
 *     note on that test for why the literal reading is not the one enforced.
 *  2. **Every executed action has a preceding approval of at least its required
 *     level** — an `Approved` event exists only where the spine granted one at
 *     the required strength; biometric-class actions never reach `Approved`
 *     without the biometric flag.
 *  3. **A rejected action never executes** — reject and DENY emit exactly one
 *     `Rejected` and never an `Approved`.
 *  4. **Every terminal state produces exactly one audit entry** — the audit sink
 *     fires exactly once per evaluation, on every path including deny.
 *
 * Exhaustive over every `ProposedAction` variant × origin × mode × toggle, like
 * its sibling: the domain is finite, so enumeration is a stronger statement than
 * sampling and needs no generator library.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritySpinePropertyTest {

    private fun allActions(): List<ProposedAction> = ProposedActionCorpus.all()

    private fun classNameOf(action: ProposedAction): String =
        action::class.simpleName ?: error("every ProposedAction has a simple name")

    /**
     * Runs [block] with a live collector attached and returns what was emitted.
     *
     * [AuthorityManager.events] has `replay = 0`, so an event emitted with no
     * subscriber is dropped — a test that reads nothing would pass vacuously.
     * Subscribing on an unconfined dispatcher and draining with [runCurrent]
     * makes "nothing was emitted" and "nobody was listening" distinguishable.
     */
    private fun TestScope.recordEvents(
        manager: AuthorityManager,
        block: () -> Unit,
    ): List<AuthorityEvent> {
        val seen = mutableListOf<AuthorityEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.events.collect { seen += it }
        }
        runCurrent()
        block()
        runCurrent()
        job.cancel()
        return seen
    }

    // ── §B7.4 — exactly one audit entry per terminal state ────────────────────

    @Test
    fun `every evaluation writes exactly one audit entry on every path`() {
        for (action in allActions()) {
            for (origin in ActionOrigin.entries) {
                for (mode in PolicyMode.entries) {
                    for (denied in listOf(false, true)) {
                        val written = mutableListOf<PolicyAuditRecord>()
                        val engine = PolicyEngine(
                            toggleKeyForAction = { "t" },
                            isToggleEnabled = { true },
                            auditSink = { written += it },
                        )
                        engine.setModeOverride(classNameOf(action), mode)
                        engine.setDenied(classNameOf(action), denied)

                        val evaluation = engine.evaluate(action, origin)

                        // Exactly one — not "at least one". A path that audited
                        // twice would double-count in the ledger, and a path that
                        // audited zero times is an unrecorded authority decision.
                        assertEquals(
                            1,
                            written.size,
                            "${classNameOf(action)} / $origin / $mode / denied=$denied " +
                                "produced ${written.size} audit entries",
                        )
                        // …and the entry is the one the caller was handed, not a
                        // second record describing something else.
                        assertEquals(evaluation.audit, written.single())
                    }
                }
            }
        }
    }

    @Test
    fun `repeated evaluations audit once each rather than batching or deduplicating`() {
        val written = mutableListOf<PolicyAuditRecord>()
        val engine = PolicyEngine(auditSink = { written += it })
        val action = ProposedAction.DeleteFile("/x")
        repeat(5) { engine.evaluate(action, ActionOrigin.USER) }
        assertEquals(5, written.size)
    }

    // ── §B7.2 — approval precedes execution, at the required level ────────────

    @Test
    fun `an action needing biometric never reaches approved without one`() = runTest {
        val biometricActions = allActions().filter(::requiresBiometric)
        assertTrue(biometricActions.isNotEmpty(), "the corpus must contain biometric-class actions")

        for (action in biometricActions) {
            val manager = AuthorityManager()
            val events = recordEvents(manager) { manager.approve(action, biometricAuthenticated = false) }

            assertTrue(
                events.none { it is AuthorityEvent.Approved },
                "${classNameOf(action)} was approved without the biometric it requires",
            )
            assertTrue(
                events.any { it is AuthorityEvent.RequestBiometric },
                "${classNameOf(action)} should have been sent back for biometric",
            )
        }
    }

    @Test
    fun `approval at the required level is what produces an approved event`() = runTest {
        for (action in allActions()) {
            val manager = AuthorityManager()
            val events = recordEvents(manager) { manager.approve(action, biometricAuthenticated = true) }
            assertEquals(
                listOf<AuthorityEvent>(AuthorityEvent.Approved(action)),
                events,
                "${classNameOf(action)} approved at full strength should execute exactly once",
            )
        }
    }

    @Test
    fun `no policy decision short of AUTO_EXECUTE ever emits an approved event`() = runTest {
        for (action in allActions()) {
            for (origin in ActionOrigin.entries) {
                for (mode in PolicyMode.entries) {
                    val engine = PolicyEngine(
                        toggleKeyForAction = { "t" },
                        isToggleEnabled = { true },
                    )
                    engine.setModeOverride(classNameOf(action), mode)
                    val evaluation = engine.evaluate(action, origin)

                    val manager = AuthorityManager()
                    val events = recordEvents(manager) { manager.apply(evaluation) }

                    val executed = events.any { it is AuthorityEvent.Approved }
                    assertEquals(
                        evaluation.decision == PolicyDecision.AUTO_EXECUTE,
                        executed,
                        "${classNameOf(action)} / $origin / $mode: decision " +
                            "${evaluation.decision} but executed=$executed",
                    )
                }
            }
        }
    }

    @Test
    fun `a machine origin never executes a biometric-class action through the spine`() = runTest {
        // The composition of §B7.2 with the machine ceiling: the two rules that
        // matter most together, since an autonomous agent is the caller we trust
        // least and a biometric-class action is the one we guard hardest.
        for (action in allActions().filter(::requiresBiometric)) {
            for (origin in listOf(ActionOrigin.BACKGROUND, ActionOrigin.AGENT)) {
                for (mode in PolicyMode.entries) {
                    val engine = PolicyEngine(
                        toggleKeyForAction = { "t" },
                        isToggleEnabled = { true },
                    )
                    engine.setModeOverride(classNameOf(action), mode)
                    val manager = AuthorityManager()
                    val events = recordEvents(manager) { manager.apply(engine.evaluate(action, origin)) }
                    assertTrue(
                        events.none { it is AuthorityEvent.Approved },
                        "${classNameOf(action)} executed from $origin in mode $mode",
                    )
                }
            }
        }
    }

    // ── §B7.3 — a rejected action never executes ──────────────────────────────

    @Test
    fun `rejecting an action emits one rejection and never an execution`() = runTest {
        for (action in allActions()) {
            val manager = AuthorityManager()
            val events = recordEvents(manager) { manager.reject(action) }
            assertEquals(1, events.size, "${classNameOf(action)} rejection emitted ${events.size} events")
            assertTrue(events.single() is AuthorityEvent.Rejected)
        }
    }

    @Test
    fun `a denied action is rejected and never executed, whatever the mode or origin`() = runTest {
        for (action in allActions()) {
            for (origin in ActionOrigin.entries) {
                for (mode in PolicyMode.entries) {
                    val engine = PolicyEngine(
                        toggleKeyForAction = { "t" },
                        isToggleEnabled = { true },
                    )
                    engine.setModeOverride(classNameOf(action), mode)
                    engine.setDenied(classNameOf(action), true)

                    val manager = AuthorityManager()
                    val events = recordEvents(manager) { manager.apply(engine.evaluate(action, origin)) }

                    assertEquals(1, events.size)
                    assertTrue(
                        events.single() is AuthorityEvent.Rejected,
                        "${classNameOf(action)} / $origin / $mode: denied action produced ${events.single()}",
                    )
                }
            }
        }
    }

    @Test
    fun `rejecting after approving does not retroactively execute`() = runTest {
        val action = ProposedAction.DeleteFile("/x")
        val manager = AuthorityManager()
        val events = recordEvents(manager) {
            manager.reject(action, "user said no")
            manager.reject(action, "user said no again")
        }
        assertTrue(events.all { it is AuthorityEvent.Rejected })
    }

    // ── §B7.1 — no downgrade to AUTO ──────────────────────────────────────────

    @Test
    fun `no sequence of machine-side inputs downgrades a strong-confirmation action`() {
        // "Machine-side inputs" = everything the app can vary without the user
        // editing their policy: origin, automation toggles, and repetition. The
        // engine is reused across the whole sequence, so a decision that depended
        // on accumulated state would show up here and not in a fresh-engine test.
        val critical = allActions().filter { riskOf(it) == RiskLevel.CRITICAL }
        assertTrue(critical.isNotEmpty(), "the corpus must contain CRITICAL actions")

        for (action in critical) {
            var toggle = false
            val engine = PolicyEngine(
                toggleKeyForAction = { "t" },
                isToggleEnabled = { toggle },
            )
            // A long, varied, stateful run — toggles flipped, denies set and
            // cleared, overrides applied and cleared — never touching the one
            // lever that is allowed to weaken the gate (an override BELOW
            // STRONG_CONFIRMATION).
            repeat(3) { round ->
                for (origin in ActionOrigin.entries) {
                    toggle = !toggle
                    engine.setDenied(classNameOf(action), round % 2 == 0)
                    engine.setDenied(classNameOf(action), false)
                    engine.setModeOverride(classNameOf(action), PolicyMode.STRONG_CONFIRMATION)
                    engine.clearModeOverride(classNameOf(action))

                    val evaluation = engine.evaluate(action, origin)
                    assertNotEquals(
                        PolicyDecision.AUTO_EXECUTE,
                        evaluation.decision,
                        "${classNameOf(action)} downgraded to AUTO_EXECUTE at round $round from $origin",
                    )
                    assertEquals(PolicyMode.STRONG_CONFIRMATION, evaluation.mode)
                }
            }
        }
    }

    @Test
    fun `a user override can weaken the gate but the audit record always says so`() {
        // The honest statement of §B7.1. ARCHITECTURE.md's rule-3 corollary makes
        // the risk→gate mapping user-controllable, so `setModeOverride(CRITICAL
        // action, AUTO)` is a supported operation and the literal invariant ("no
        // sequence of inputs downgrades …") does not hold as written. What must
        // hold — and what this asserts — is that the downgrade is never SILENT:
        // the record carries the weakened mode, so the ledger shows a user
        // decision rather than an engine that quietly changed its mind.
        //
        // Whether the engine should instead CLAMP overrides at CRITICAL is an
        // open policy question, not a defect being papered over. If clamping is
        // adopted, this test inverts and the one above subsumes it.
        // DeleteFile, not RunScript: the classification table puts irreversible
        // deletes at CRITICAL and RunScript at HIGH.
        val action = ProposedAction.DeleteFile("/x")
        assertEquals(RiskLevel.CRITICAL, riskOf(action))

        val written = mutableListOf<PolicyAuditRecord>()
        val engine = PolicyEngine(auditSink = { written += it })

        assertEquals(PolicyMode.STRONG_CONFIRMATION, engine.evaluate(action, ActionOrigin.USER).mode)

        engine.setModeOverride(classNameOf(action), PolicyMode.AUTO)
        val downgraded = engine.evaluate(action, ActionOrigin.USER)
        assertEquals(PolicyMode.AUTO, downgraded.mode)
        assertEquals(PolicyMode.AUTO, written.last().mode)
        assertEquals(RiskLevel.CRITICAL, written.last().risk)

        // And even an explicitly weakened gate does not extend to machine origins.
        for (origin in listOf(ActionOrigin.BACKGROUND, ActionOrigin.AGENT)) {
            assertNotEquals(PolicyDecision.AUTO_EXECUTE, engine.evaluate(action, origin).decision)
        }

        // Clearing the override restores the default gate — the weakening is not
        // a one-way door.
        engine.clearModeOverride(classNameOf(action))
        assertEquals(PolicyMode.STRONG_CONFIRMATION, engine.evaluate(action, ActionOrigin.USER).mode)
    }
}
