package com.newax.aegis.engine.intelligence

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.authority.InMemoryPolicyStore
import com.newax.aegis.authority.PolicyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Track A4 + A7 — the privileged-skill → policy-action mapping the GoalExecutor
 * evaluates before running a task (rule 10: a plan grants zero execution
 * authority), and the planner's plan-time policy pre-flight that warns about
 * tasks the policy spine will refuse autonomously.
 *
 * Read-only/analysis skills carry no policy action; privileged ones map to a
 * representative whose risk class drives the default policy mode
 * (communication → HIGH approval, calendar writes → HIGH, app/media launch →
 * LOW auto, deletion → CRITICAL strong).
 */
class SkillPolicyTest {

    // A real pure-KMP engine with default mappings (no toggles, no overrides) —
    // the same default risk→mode table production uses.
    private fun engine(): PolicyEngine = PolicyEngine(
        store = InMemoryPolicyStore(),
        toggleKeyForAction = { null },
        isToggleEnabled = { false },
        auditSink = {}
    )

    @Test
    fun `communication and calendar skills map to high-risk actions`() {
        assertEquals(ProposedAction.Send(""), SkillRegistry.policyActionFor("send_message"))
        assertEquals(ProposedAction.CreateEvent("", ""), SkillRegistry.policyActionFor("set_reminder"))
    }

    @Test
    fun `app and media launch map to low-risk open-app actions`() {
        assertEquals(ProposedAction.OpenApp(""), SkillRegistry.policyActionFor("launch_app"))
        assertEquals(ProposedAction.OpenApp(""), SkillRegistry.policyActionFor("play_media"))
    }

    @Test
    fun `read-only and analysis skills have no policy gate`() {
        listOf(
            "find_app", "find_contact", "find_file", "execute_search",
            "analyze_request", "generate_summary",
        ).forEach { skillId ->
            assertNull("'$skillId' must not carry a policy action", SkillRegistry.policyActionFor(skillId))
        }
    }

    // ── Track A7: plan-time policy pre-flight ────────────────────────────────

    @Test
    fun `plan-time preflight flags privileged skills policy will refuse`() {
        val eng = engine()
        val tasks = listOf(
            TaskNode(goalId = "g", description = "find app", skillId = "find_app"),
            TaskNode(goalId = "g", description = "send message", skillId = "send_message"),
        )

        val warnings = policyPreflightWarnings(tasks) { action -> eng.evaluate(action, ActionOrigin.AGENT) }

        assertEquals(1, warnings.size)
        assertTrue("names the task", warnings[0].contains("send message"))
        assertTrue("names the decision", warnings[0].contains("REQUIRE_APPROVAL"))
    }

    @Test
    fun `plan-time preflight stays silent for auto-executed skills`() {
        val eng = engine()
        val tasks = listOf(
            TaskNode(goalId = "g", description = "find app", skillId = "find_app"),
            TaskNode(goalId = "g", description = "launch app", skillId = "launch_app"),
        )

        val warnings = policyPreflightWarnings(tasks) { action -> eng.evaluate(action, ActionOrigin.AGENT) }

        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `plan-time preflight is silent when the policy engine is unavailable`() {
        val tasks = listOf(
            TaskNode(goalId = "g", description = "send message", skillId = "send_message")
        )

        // Mirrors plan() before bootstrap / in JVM tests: PolicyHolder.engineOrNull()
        // is null, so no evaluation happens and no warning is produced — never a crash.
        val warnings = policyPreflightWarnings(tasks) { null }

        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `plan-time preflight skips read-only skills entirely`() {
        val eng = engine()
        val tasks = listOf(
            TaskNode(goalId = "g", description = "determine scope", skillId = "determine_scope"),
            TaskNode(goalId = "g", description = "execute search", skillId = "execute_search"),
            TaskNode(goalId = "g", description = "present results", skillId = "present_results"),
        )

        val warnings = policyPreflightWarnings(tasks) { action -> eng.evaluate(action, ActionOrigin.AGENT) }

        assertTrue(warnings.isEmpty())
    }
}
