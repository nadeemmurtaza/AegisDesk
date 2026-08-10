package com.newax.aegis.desktop.planner

import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.InMemoryPlatformCapabilityRegistry
import com.newax.aegis.platform.PlatformCapability
import com.newax.aegis.platform.PlatformCapabilityRegistry
import com.newax.aegis.platform.PrivilegeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5f — desktop planner resolves skills through the platform capability
 * contract ([com.newax.aegis.platform.CapabilityResolver]), mirroring Android's
 * GoalPlanner pre-flight.
 *
 * OS-independent by construction: the planner takes the registry as a parameter,
 * so the tests inject a fake Desktop capability with a forced status instead of
 * depending on whether the real WindowsDesktopCapability reports READY
 * (Windows) or NOT_SUPPORTED (Linux CI) for the host OS.
 */
class DesktopPlannerTest {

    @Test
    fun `plan is feasible when the desktop capability is ready`() {
        val plan = DesktopGoalPlanner.plan("open spotify", registryWith(CapabilityStatus.READY))

        assertEquals("open", plan.goal.intent)
        assertEquals(listOf("find_app", "launch_app"), plan.tasks.map { it.skillId })
        assertTrue("expected feasible", plan.feasible)
        assertTrue(plan.missingSkills.isEmpty())
        assertTrue("no blocked capability", plan.missingCapabilities.isEmpty())
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `plan is blocked when the contract reports NOT_SUPPORTED`() {
        val plan = DesktopGoalPlanner.plan("open spotify", registryWith(CapabilityStatus.NOT_SUPPORTED))

        assertFalse("expected infeasible", plan.feasible)
        assertEquals(listOf("OPEN_APP"), plan.missingCapabilities)
        // The warning names the live status and the candidate surface, so the
        // user sees *why* the goal is blocked — the runner's missing-capabilities block.
        val warning = plan.warnings.single()
        assertTrue(warning.contains("OPEN_APP"))
        assertTrue(warning.contains("NOT_SUPPORTED"))
        assertTrue(warning.contains("PROCESSES, DESKTOP"))
    }

    @Test
    fun `plan is blocked when no capability is registered`() {
        val plan = DesktopGoalPlanner.plan("open spotify", null)

        assertFalse("expected infeasible", plan.feasible)
        assertEquals(listOf("OPEN_APP"), plan.missingCapabilities)
        assertTrue(plan.warnings.single().contains("no registered capability"))
    }

    @Test
    fun `send resolves through the desktop capability when it is the only registered surface`() {
        // SEND_TEXT has four candidate surfaces (DESKTOP, SMS, NOTIFICATIONS,
        // CONTACTS); with only DESKTOP registered and READY, the contract picks
        // it — skills resolve through the registry, not ad-hoc name matching.
        val plan = DesktopGoalPlanner.plan("send a message", registryWith(CapabilityStatus.READY))

        assertEquals("send", plan.goal.intent)
        assertEquals(listOf("find_contact", "send_message"), plan.tasks.map { it.skillId })
        assertTrue("expected feasible", plan.feasible)
        assertTrue(plan.missingCapabilities.isEmpty())
    }

    @Test
    fun `model-tier skills are never platform-blocked`() {
        // LLM has no platform backing (the model layer owns it), so the planner
        // must not report it as blocked even with an empty registry.
        val plan = DesktopGoalPlanner.plan("summarize this document", null)

        assertEquals("summarize", plan.goal.intent)
        assertTrue("model-tier skill must not block the plan", plan.feasible)
        assertTrue(plan.missingCapabilities.isEmpty())
    }

    @Test
    fun `unknown intent falls back to analyze_request`() {
        val plan = DesktopGoalPlanner.plan("hello there", null)

        assertEquals("general", plan.goal.intent)
        assertEquals(listOf("analyze_request"), plan.tasks.map { it.skillId })
        assertTrue(plan.feasible)
    }

    @Test
    fun `every decomposition step references a registered skill`() {
        // Close the call graph: the planner must never emit a skill id the
        // registry does not know, or missingSkills becomes noise.
        val goals = listOf(
            "open spotify", "send a message", "find my notes", "search the web",
            "play a song", "summarize this", "remind me later", "call ali", "share a file", "hello",
        )
        goals.forEach { description ->
            val plan = DesktopGoalPlanner.plan(description, null)
            plan.tasks.forEach { task ->
                assertTrue(
                    "skill '${task.skillId}' from plan of '$description' is not registered",
                    SkillRegistry.has(task.skillId),
                )
            }
        }
    }

    @Test
    fun `skill registry mirrors the desktop surface`() {
        assertEquals(listOf("OPEN_APP"), SkillRegistry.get("launch_app")?.requiredCapabilities)
        assertEquals(listOf("SEND_TEXT"), SkillRegistry.get("send_message")?.requiredCapabilities)
        assertEquals(listOf("PLAY_MEDIA"), SkillRegistry.get("play_media")?.requiredCapabilities)
        assertEquals(listOf("LLM"), SkillRegistry.get("generate_summary")?.requiredCapabilities)
        assertTrue(SkillRegistry.get("find_app")?.requiredCapabilities.isNullOrEmpty())
    }

    /** Fake Desktop capability whose status is forced, independent of the host OS. */
    private class FakeDesktopCapability(
        private val status: CapabilityStatus,
    ) : PlatformCapability {
        override val id: CapabilityId get() = CapabilityId.DESKTOP
        override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
            id = id,
            version = 1,
            displayName = "Desktop",
            description = "fake desktop capability for planner tests",
            privilegeLevel = PrivilegeLevel.HIGH_IMPACT_SYSTEM,
            status = status,
        )
    }

    private fun registryWith(status: CapabilityStatus): PlatformCapabilityRegistry =
        InMemoryPlatformCapabilityRegistry().apply { register(FakeDesktopCapability(status)) }
}
