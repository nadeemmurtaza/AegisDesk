package com.newax.aegis.engine.intelligence

import com.newax.aegis.PlatformCapabilitiesHolder
import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.InMemoryPlatformCapabilityRegistry
import com.newax.aegis.platform.PlatformCapability
import com.newax.aegis.platform.PlatformCapabilityRegistry
import com.newax.aegis.platform.PrivilegeLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Track A3 — Android's planner resolves skill requirements through the shared
 * [com.newax.aegis.platform.CapabilityResolver] contract (the desktop-parity
 * slice). Pure JVM: fake registries via the [PlatformCapabilitiesHolder] test
 * seam, mirroring the desktop's DesktopPlannerTest shape. Verifies the plan
 * pre-flight is an honest function of the registered registry: a ready
 * capability makes a gated goal feasible, a blocked/absent one is reported
 * with the exact capability name, status and candidates, and a *missing*
 * registry is "blocked", never silently "feasible" (the A3 named failure
 * mode — a null registry cannot crash and cannot fake success).
 */
class GoalPlannerCapabilityTest {

    @Before
    fun setUp() {
        PlatformCapabilitiesHolder.setRegistryForTest(InMemoryPlatformCapabilityRegistry())
    }

    @After
    fun tearDown() {
        PlatformCapabilitiesHolder.setRegistryForTest(null)
    }

    private fun fakeCapability(id: CapabilityId, status: CapabilityStatus = CapabilityStatus.READY) =
        object : PlatformCapability {
            override val id: CapabilityId = id
            override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
                id = id,
                version = 1,
                displayName = id.name,
                description = "test capability",
                privilegeLevel = PrivilegeLevel.READ_ONLY,
                status = status,
            )
            override fun status(): CapabilityStatus = status
        }

    private fun registryOf(vararg capabilities: Pair<CapabilityId, CapabilityStatus>): PlatformCapabilityRegistry =
        InMemoryPlatformCapabilityRegistry().apply {
            capabilities.forEach { (id, status) -> register(fakeCapability(id, status)) }
        }

    // "open spotify" decomposes to find_app (ungated) + launch_app (requires OPEN_APP),
    // so OPEN_APP is the one platform-gated requirement under test.

    @Test
    fun `ready capability makes a gated goal feasible`() {
        PlatformCapabilitiesHolder.setRegistryForTest(registryOf(CapabilityId.PROCESSES to CapabilityStatus.READY))

        val plan = GoalPlanner.plan("open spotify")

        assertTrue("feasible when OPEN_APP is backed", plan.feasible)
        assertTrue(plan.missingCapabilities.isEmpty())
        assertTrue("no capability warning for a covered requirement", plan.warnings.none { it.contains("not ready") })
    }

    @Test
    fun `absent capability is reported blocked with candidates`() {
        // Empty registry: nothing registered, so OPEN_APP is blocked with null
        // status and the candidate surfaces named in the warning.
        val plan = GoalPlanner.plan("open spotify")

        assertFalse(plan.feasible)
        assertEquals(listOf("OPEN_APP"), plan.missingCapabilities)
        val warning = plan.warnings.first { it.contains("OPEN_APP") }
        assertTrue("names the status as no registered capability", warning.contains("no registered capability"))
        assertTrue("names the candidate surface", warning.contains("PROCESSES"))
        assertTrue("names the candidate surface", warning.contains("DESKTOP"))
    }

    @Test
    fun `uninitialized registry is an honest blocked, never silently feasible`() {
        // Bootstrap hasn't run (null registry) — the pre-flight must not crash
        // and must not pretend a gated goal can run: desktop-parity, it is
        // blocked with the candidates named.
        PlatformCapabilitiesHolder.setRegistryForTest(null)

        val plan = GoalPlanner.plan("open spotify")

        assertFalse("a missing registry never fabricates feasibility", plan.feasible)
        assertEquals(listOf("OPEN_APP"), plan.missingCapabilities)
        val warning = plan.warnings.first { it.contains("OPEN_APP") }
        assertTrue(warning.contains("no registered capability"))
        assertTrue(warning.contains("PROCESSES"))
    }

    @Test
    fun `model-tier goals stay feasible without any registry`() {
        // LLM is not platform-gated (unmapped tier) — a summarize goal must stay
        // feasible even before bootstrap, with no capability warning at all.
        PlatformCapabilitiesHolder.setRegistryForTest(null)

        val plan = GoalPlanner.plan("summarize the meeting notes")

        assertTrue("LLM-only goal is feasible without a registry", plan.feasible)
        assertTrue(plan.missingCapabilities.isEmpty())
        assertTrue(plan.warnings.none { it.contains("not ready") })
    }

    @Test
    fun `blocked registered capability reports its live status`() {
        // PROCESSES is registered but UNAVAILABLE — the warning must carry the
        // real status (the Goals screen shows status + candidates, not a guess).
        PlatformCapabilitiesHolder.setRegistryForTest(registryOf(CapabilityId.PROCESSES to CapabilityStatus.UNAVAILABLE))

        val plan = GoalPlanner.plan("open spotify")

        assertFalse(plan.feasible)
        assertEquals(listOf("OPEN_APP"), plan.missingCapabilities)
        val warning = plan.warnings.first { it.contains("OPEN_APP") }
        assertTrue("carries the live status", warning.contains("UNAVAILABLE"))
        assertTrue("still names the candidates", warning.contains("PROCESSES"))
    }
}
