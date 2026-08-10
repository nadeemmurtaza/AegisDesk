package com.newax.aegis.authority

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.assistant.RiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Track A1 — PolicyEngine (the authority spine's policy layer, ARCHITECTURE.md
 * rule 3 corollary). Pure KMP: no platform imports, injected toggle seam, so
 * the decision table is verified on every target.
 */
class PolicyEngineTest {

    // ── Default mapping (the ARCHITECTURE.md corollary: LOW→AUTO, MEDIUM→CONFIGURABLE,
    //    HIGH→APPROVAL, CRITICAL→STRONG_CONFIRMATION) ─────────────────────────────

    @Test
    fun `default mapping follows the corollary for every risk level`() {
        assertEquals(PolicyMode.AUTO, PolicyEngine.defaultModeFor(RiskLevel.LOW))
        assertEquals(PolicyMode.CONFIGURABLE, PolicyEngine.defaultModeFor(RiskLevel.MEDIUM))
        assertEquals(PolicyMode.APPROVAL, PolicyEngine.defaultModeFor(RiskLevel.HIGH))
        assertEquals(PolicyMode.STRONG_CONFIRMATION, PolicyEngine.defaultModeFor(RiskLevel.CRITICAL))
    }

    @Test
    fun `low risk actions auto-execute`() {
        val engine = PolicyEngine()
        val evaluation = engine.evaluate(ProposedAction.SearchAll("q"), ActionOrigin.USER)
        assertEquals(PolicyDecision.AUTO_EXECUTE, evaluation.decision)
        assertEquals(PolicyMode.AUTO, evaluation.mode)
    }

    @Test
    fun `medium risk actions are configurable and gate on the toggle`() {
        val engine = PolicyEngine(
            toggleKeyForAction = { "auto_tap" },
            isToggleEnabled = { key -> key == "auto_tap" },
        )
        assertEquals(
            PolicyDecision.AUTO_EXECUTE,
            engine.evaluate(ProposedAction.Tap("ok"), ActionOrigin.USER).decision,
        )
        val gated = PolicyEngine(toggleKeyForAction = { "auto_tap" }, isToggleEnabled = { false })
        assertEquals(
            PolicyDecision.REQUIRE_APPROVAL,
            gated.evaluate(ProposedAction.Tap("ok"), ActionOrigin.USER).decision,
        )
    }

    @Test
    fun `configurable with no mapped toggle conservatively requires approval`() {
        val engine = PolicyEngine(toggleKeyForAction = { null }, isToggleEnabled = { true })
        assertEquals(
            PolicyDecision.REQUIRE_APPROVAL,
            engine.evaluate(ProposedAction.ApproveDraft("d1"), ActionOrigin.USER).decision,
        )
    }

    @Test
    fun `high risk actions require approval even with a toggle on`() {
        val engine = PolicyEngine(toggleKeyForAction = { "auto_send_message" }, isToggleEnabled = { true })
        val evaluation = engine.evaluate(ProposedAction.Send("hi"), ActionOrigin.USER)
        assertEquals(PolicyDecision.REQUIRE_APPROVAL, evaluation.decision)
        assertEquals(PolicyMode.APPROVAL, evaluation.mode)
    }

    @Test
    fun `critical risk actions require strong confirmation`() {
        val evaluation = PolicyEngine().evaluate(ProposedAction.DeleteFile("/tmp/x"), ActionOrigin.USER)
        assertEquals(PolicyDecision.REQUIRE_STRONG, evaluation.decision)
        assertEquals(PolicyMode.STRONG_CONFIRMATION, evaluation.mode)
    }

    // ── Background-origin ceiling (machine text never carries user authority) ────

    @Test
    fun `background origin never auto-executes at or above the ceiling even in AUTO mode`() {
        // Send is HIGH; an override to AUTO must not let background text auto-send.
        val engine = PolicyEngine().apply { setModeOverride("Send", PolicyMode.AUTO) }
        val evaluation = engine.evaluate(ProposedAction.Send("hi"), ActionOrigin.BACKGROUND)
        assertEquals(PolicyDecision.REQUIRE_APPROVAL, evaluation.decision)
        assertTrue(evaluation.reason.contains("background"))
    }

    @Test
    fun `background origin still auto-executes low risk actions in AUTO mode`() {
        val evaluation = PolicyEngine().evaluate(ProposedAction.SearchAll("q"), ActionOrigin.BACKGROUND)
        assertEquals(PolicyDecision.AUTO_EXECUTE, evaluation.decision)
    }

    // ── Agent origin (autonomous executor) — stricter than user, like background ──

    @Test
    fun `agent origin never auto-executes at or above the ceiling even in AUTO mode`() {
        // An autonomous goal executor sending a message must not do so without a
        // human, no matter how the user configured the Send class (rule 10).
        val engine = PolicyEngine().apply { setModeOverride("Send", PolicyMode.AUTO) }
        val evaluation = engine.evaluate(ProposedAction.Send("hi"), ActionOrigin.AGENT)
        assertEquals(PolicyDecision.REQUIRE_APPROVAL, evaluation.decision)
        assertTrue(evaluation.reason.contains("machine origin"))
    }

    @Test
    fun `agent origin still auto-executes low risk actions in AUTO mode`() {
        val evaluation = PolicyEngine().evaluate(ProposedAction.SearchAll("q"), ActionOrigin.AGENT)
        assertEquals(PolicyDecision.AUTO_EXECUTE, evaluation.decision)
    }

    @Test
    fun `agent origin in CONFIGURABLE mode follows the toggle below the ceiling`() {
        val engine = PolicyEngine(
            toggleKeyForAction = { "auto_tap" },
            isToggleEnabled = { key -> key == "auto_tap" },
        )
        assertEquals(
            PolicyDecision.AUTO_EXECUTE,
            engine.evaluate(ProposedAction.Tap("ok"), ActionOrigin.AGENT).decision,
        )
    }

    // ── Rule 10: plans grant zero execution authority ───────────────────────────

    @Test
    fun `allowsAutonomousExecution is true only for AUTO_EXECUTE`() {
        assertTrue(PolicyDecision.AUTO_EXECUTE.allowsAutonomousExecution)
        assertFalse(PolicyDecision.REQUIRE_APPROVAL.allowsAutonomousExecution)
        assertFalse(PolicyDecision.REQUIRE_STRONG.allowsAutonomousExecution)
        assertFalse(PolicyDecision.DENY.allowsAutonomousExecution)
    }

    // ── User-controllable mapping ───────────────────────────────────────────────

    @Test
    fun `user override replaces the default mode and clears back to it`() {
        val engine = PolicyEngine(
            toggleKeyForAction = { "auto_send_message" },
            isToggleEnabled = { true },
        )
        engine.setModeOverride("Send", PolicyMode.CONFIGURABLE)
        assertEquals(
            PolicyDecision.AUTO_EXECUTE,
            engine.evaluate(ProposedAction.Send("hi"), ActionOrigin.USER).decision,
        )
        engine.clearModeOverride("Send")
        assertEquals(
            PolicyDecision.REQUIRE_APPROVAL,
            engine.evaluate(ProposedAction.Send("hi"), ActionOrigin.USER).decision,
        )
    }

    @Test
    fun `effectiveMode reflects override then default`() {
        val engine = PolicyEngine()
        assertEquals(PolicyMode.APPROVAL, engine.effectiveMode(ProposedAction.Send("hi")))
        engine.setModeOverride("Send", PolicyMode.AUTO)
        assertEquals(PolicyMode.AUTO, engine.effectiveMode(ProposedAction.Send("hi")))
    }

    // ── Hard deny beats every mode ──────────────────────────────────────────────

    @Test
    fun `denied action class is refused regardless of mode and origin`() {
        val engine = PolicyEngine().apply { setDenied("RunScript", true) }
        val evaluation = engine.evaluate(ProposedAction.RunScript("x"), ActionOrigin.USER)
        assertEquals(PolicyDecision.DENY, evaluation.decision)
        assertTrue(evaluation.reason.contains("denies"))
    }

    // ── Audit trail ─────────────────────────────────────────────────────────────

    @Test
    fun `every evaluation emits a complete audit record`() {
        val records = mutableListOf<PolicyAuditRecord>()
        val engine = PolicyEngine(auditSink = { records.add(it) })
        engine.evaluate(ProposedAction.DeleteFile("/tmp/x"), ActionOrigin.USER)

        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("DeleteFile", record.actionClass)
        assertEquals(ActionOrigin.USER, record.origin)
        assertEquals(RiskLevel.CRITICAL, record.risk)
        assertEquals(PolicyMode.STRONG_CONFIRMATION, record.mode)
        assertEquals(PolicyDecision.REQUIRE_STRONG, record.decision)
        assertTrue(record.reason.isNotBlank())
        assertTrue(record.auditedAtMs > 0)
    }

    @Test
    fun `in-memory store round-trips overrides and denies`() {
        val store = InMemoryPolicyStore()
        store.setModeOverride("Send", PolicyMode.AUTO)
        store.setDenied("RunScript", true)
        assertEquals(PolicyMode.AUTO, store.modeOverride("Send"))
        assertTrue(store.isDenied("RunScript"))
        store.clearModeOverride("Send")
        store.setDenied("RunScript", false)
        assertEquals(null, store.modeOverride("Send"))
        assertFalse(store.isDenied("RunScript"))
    }
}
