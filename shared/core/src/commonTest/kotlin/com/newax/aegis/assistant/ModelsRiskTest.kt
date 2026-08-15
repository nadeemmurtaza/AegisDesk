package com.newax.aegis.assistant

import com.newax.aegis.authority.PolicyEngine
import com.newax.aegis.authority.PolicyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the canonical action-risk table (ARCHITECTURE.md concept registry,
 * T2.2): `RiskLevel` is the ONLY action-risk vocabulary, classification comes
 * from [riskOf] / [ProposedAction.riskLevel], the machine ceiling is one
 * constant, and the RiskLevel→PolicyMode default mapping is single-sourced in
 * [PolicyEngine.defaultModeFor]. These pins exist because a UI badge used to
 * re-classify actions with its own `Risk {Routine, Sensitive, HighImpact}`
 * enum and silently diverged (deletes under-classified, calendar events
 * under-classified, searches over-classified) — that enum is retired, and any
 * future consumer that re-derives a table gets caught by a diff like this.
 */
class ModelsRiskTest {

    // ── The canonical classification table (riskOf) ────────────────────────────

    @Test
    fun `irreversible destructive actions are CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, riskOf(ProposedAction.DeleteFile("/x")))
        assertEquals(RiskLevel.CRITICAL, riskOf(ProposedAction.DeleteContact("c1")))
        assertEquals(RiskLevel.CRITICAL, riskOf(ProposedAction.DeleteProject("p1")))
        assertEquals(RiskLevel.CRITICAL, riskOf(ProposedAction.ForgetFact("personal", "f")))
        assertEquals(RiskLevel.CRITICAL, riskOf(ProposedAction.RejectAllDrafts))
    }

    @Test
    fun `outward-facing and executing actions are HIGH`() {
        assertEquals(RiskLevel.HIGH, riskOf(ProposedAction.Send("hi")))
        assertEquals(RiskLevel.HIGH, riskOf(ProposedAction.SendImage("img")))
        assertEquals(RiskLevel.HIGH, riskOf(ProposedAction.PostSocialMedia("pkg", "cap", "img", "alt")))
        assertEquals(RiskLevel.HIGH, riskOf(ProposedAction.RunScript("code")))
        assertEquals(RiskLevel.HIGH, riskOf(ProposedAction.CreateEvent("t", "now")))
        assertEquals(RiskLevel.HIGH, riskOf(ProposedAction.ReplyNotification("k", "ok")))
    }

    @Test
    fun `state-changing but reversible actions are MEDIUM`() {
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.Tap("ok")))
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.TapPixels(1f, 2f)))
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.Type("text")))
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.UpdateMemory("cat", "info")))
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.UpdateGraph("a", "r", "b")))
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.UpdateNode("id", "k", "v")))
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.LogCommunication("c", "s")))
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.UpdateProject("id", "st", "n")))
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.ApproveDraft("d1")))
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.ApproveAllDrafts))
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.MergeContacts("a", "b")))
        assertEquals(RiskLevel.MEDIUM, riskOf(ProposedAction.AnalyzeContacts()))
    }

    @Test
    fun `read-only and navigational actions are LOW`() {
        assertEquals(RiskLevel.LOW, riskOf(ProposedAction.OpenApp("WhatsApp")))
        assertEquals(RiskLevel.LOW, riskOf(ProposedAction.SearchAll("q")))
        assertEquals(RiskLevel.LOW, riskOf(ProposedAction.PrefixSearch("p")))
        assertEquals(RiskLevel.LOW, riskOf(ProposedAction.QueryCalendar("week")))
        assertEquals(RiskLevel.LOW, riskOf(ProposedAction.Home))
        assertEquals(RiskLevel.LOW, riskOf(ProposedAction.Recents))
        assertEquals(RiskLevel.LOW, riskOf(ProposedAction.Back))
        assertEquals(RiskLevel.LOW, riskOf(ProposedAction.Scroll(true)))
        assertEquals(RiskLevel.LOW, riskOf(ProposedAction.AuditSecurity))
        assertEquals(RiskLevel.LOW, riskOf(ProposedAction.TakeScreenshot))
        assertEquals(RiskLevel.LOW, riskOf(ProposedAction.ShowDrafts))
    }

    @Test
    fun `riskLevel extension matches riskOf`() {
        assertEquals(riskOf(ProposedAction.Send("hi")), ProposedAction.Send("hi").riskLevel)
        assertEquals(riskOf(ProposedAction.DeleteFile("/x")), ProposedAction.DeleteFile("/x").riskLevel)
        assertEquals(riskOf(ProposedAction.Home), ProposedAction.Home.riskLevel)
    }

    // ── Ordering is severity — ceilings and biometrics depend on it ────────────

    @Test
    fun `risk ordering is severity`() {
        assertTrue(RiskLevel.CRITICAL > RiskLevel.HIGH)
        assertTrue(RiskLevel.HIGH > RiskLevel.MEDIUM)
        assertTrue(RiskLevel.MEDIUM > RiskLevel.LOW)
    }

    @Test
    fun `machine ceiling is the single canonical constant`() {
        assertEquals(RiskLevel.HIGH, MACHINE_AUTO_EXECUTE_CEILING)
        assertTrue(RiskLevel.CRITICAL >= MACHINE_AUTO_EXECUTE_CEILING)
        assertTrue(RiskLevel.MEDIUM < MACHINE_AUTO_EXECUTE_CEILING)
    }

    @Test
    fun `machine origin never auto-executes at or above the ceiling`() {
        // HIGH action (Send) from a machine origin with the toggle ON still refuses.
        assertFalse(mayAutoExecute(ProposedAction.Send("hi"), ActionOrigin.BACKGROUND, toggleEnabled = true))
        assertFalse(mayAutoExecute(ProposedAction.Send("hi"), ActionOrigin.AGENT, toggleEnabled = true))
        // CRITICAL action (DeleteFile) from a machine origin — refused.
        assertFalse(mayAutoExecute(ProposedAction.DeleteFile("/x"), ActionOrigin.BACKGROUND, toggleEnabled = true))
        // BELOW the ceiling a machine origin may auto-execute when the toggle is on.
        assertTrue(mayAutoExecute(ProposedAction.Tap("ok"), ActionOrigin.BACKGROUND, toggleEnabled = true))
        // USER origin is not machine-blocked at any risk.
        assertTrue(mayAutoExecute(ProposedAction.DeleteFile("/x"), ActionOrigin.USER, toggleEnabled = true))
    }

    @Test
    fun `biometric and warnings are consistent with the table`() {
        assertTrue(requiresBiometric(ProposedAction.Send("hi")))       // HIGH
        assertTrue(requiresBiometric(ProposedAction.DeleteFile("/x"))) // CRITICAL
        assertFalse(requiresBiometric(ProposedAction.Tap("ok")))       // MEDIUM
        assertFalse(requiresBiometric(ProposedAction.SearchAll("q")))  // LOW

        assertNotNull(ProposedAction.DeleteFile("/x").confirmationWarning)
        assertNotNull(ProposedAction.Send("hi").confirmationWarning)
        assertNull(ProposedAction.Tap("ok").confirmationWarning)
    }

    // ── The action → risk → gate chain is single-sourced ──────────────────────

    @Test
    fun `every action's default gate follows the single defaultModeFor mapping`() {
        assertEquals(PolicyMode.STRONG_CONFIRMATION, PolicyEngine.defaultModeFor(riskOf(ProposedAction.DeleteFile("/x"))))
        assertEquals(PolicyMode.APPROVAL, PolicyEngine.defaultModeFor(riskOf(ProposedAction.Send("hi"))))
        assertEquals(PolicyMode.CONFIGURABLE, PolicyEngine.defaultModeFor(riskOf(ProposedAction.Tap("ok"))))
        assertEquals(PolicyMode.AUTO, PolicyEngine.defaultModeFor(riskOf(ProposedAction.SearchAll("q"))))
    }

    @Test
    fun `effectiveMode agrees with the default mapping when no override exists`() {
        val engine = PolicyEngine()
        assertEquals(PolicyMode.STRONG_CONFIRMATION, engine.effectiveMode(ProposedAction.DeleteFile("/x")))
        assertEquals(PolicyMode.APPROVAL, engine.effectiveMode(ProposedAction.Send("hi")))
        assertEquals(PolicyMode.CONFIGURABLE, engine.effectiveMode(ProposedAction.Tap("ok")))
        assertEquals(PolicyMode.AUTO, engine.effectiveMode(ProposedAction.SearchAll("q")))
    }
}
