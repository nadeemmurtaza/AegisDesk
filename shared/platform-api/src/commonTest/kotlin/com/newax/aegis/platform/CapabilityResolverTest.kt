package com.newax.aegis.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityResolverTest {

    private fun fakeCapability(id: CapabilityId, status: CapabilityStatus = CapabilityStatus.READY) =
        object : PlatformCapability {
            override val id: CapabilityId = id
            override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
                id = id,
                version = 1,
                displayName = id.name,
                description = "test capability",
                privilegeLevel = PrivilegeLevel.READ_ONLY,
                status = status
            )
            override fun status(): CapabilityStatus = status
        }

    private fun registryOf(vararg capabilities: Pair<CapabilityId, CapabilityStatus>): PlatformCapabilityRegistry =
        InMemoryPlatformCapabilityRegistry().apply {
            capabilities.forEach { (id, status) -> register(fakeCapability(id, status)) }
        }

    @Test
    fun readyCapabilityCoversSkillRequirement() {
        val registry = registryOf(CapabilityId.PROCESSES to CapabilityStatus.READY)
        val resolution = CapabilityResolver.resolve(registry, "OPEN_APP")
        assertFalse(resolution.isBlocked)
        assertEquals(CapabilityStatus.READY, resolution.status)
        assertEquals(listOf(CapabilityId.PROCESSES, CapabilityId.DESKTOP), resolution.candidates)
    }

    @Test
    fun prefersReadyCandidateOverEarlierRegisteredOne() {
        // DESKTOP is the preferred candidate but not ready; SMS is ready but a
        // later preference. The first *ready* candidate wins, so the resolution
        // is operational even though the preferred candidate is blocked.
        val registry = registryOf(
            CapabilityId.DESKTOP to CapabilityStatus.UNAVAILABLE,
            CapabilityId.SMS to CapabilityStatus.READY
        )
        val resolution = CapabilityResolver.resolve(registry, "SEND_TEXT")
        assertFalse(resolution.isBlocked)
        assertEquals(CapabilityStatus.READY, resolution.status)
        // Preference order is preserved on candidates; DESKTOP stays first.
        assertEquals(CapabilityId.DESKTOP, resolution.candidates.first())
    }

    @Test
    fun blockedCapabilityReportsStatusOfFirstRegisteredCandidate() {
        val registry = registryOf(CapabilityId.DESKTOP to CapabilityStatus.UNAVAILABLE)
        val resolution = CapabilityResolver.resolve(registry, "SEND_TEXT")
        assertTrue(resolution.isBlocked)
        assertEquals(CapabilityStatus.UNAVAILABLE, resolution.status)
        // Reason is visible: the accessibility seam is what flips DESKTOP to READY.
        assertTrue("DESKTOP" in resolution.candidates.joinToString())
    }

    @Test
    fun unregisteredCapabilityIsBlockedWithNullStatus() {
        val registry = InMemoryPlatformCapabilityRegistry()
        val resolution = CapabilityResolver.resolve(registry, "OPEN_APP")
        assertTrue(resolution.isBlocked)
        assertNull(resolution.status)
        assertFalse(resolution.candidates.isEmpty())
    }

    @Test
    fun unmappedModelTierCapabilityIsNotPlatformGated() {
        val registry = InMemoryPlatformCapabilityRegistry()
        val resolution = CapabilityResolver.resolve(registry, "LLM")
        assertFalse(resolution.isBlocked)
        assertTrue(resolution.candidates.isEmpty())
        assertNull(resolution.status)
    }

    @Test
    fun enumNameContainedInNameFallsBackToThatCapability() {
        val registry = registryOf(CapabilityId.FILES to CapabilityStatus.READY)
        val resolution = CapabilityResolver.resolve(registry, "FILE_WRITE")
        assertFalse(resolution.isBlocked)
        assertEquals(CapabilityId.FILES, resolution.candidates.first())
    }

    @Test
    fun missingFiltersOnlyPlatformGatedRequirements() {
        val registry = registryOf(CapabilityId.FILES to CapabilityStatus.READY)
        val missing = CapabilityResolver.missing(
            registry,
            listOf("OPEN_APP", "FILE_WRITE", "LLM", "SEND_TEXT")
        )
        // OPEN_APP (nothing registered) and SEND_TEXT (nothing registered) are
        // gated and blocked; FILE_WRITE is covered; LLM is not platform-owned.
        assertEquals(listOf("OPEN_APP", "SEND_TEXT"), missing)
    }

    @Test
    fun missingReportsDisabledAndPermissionBlockedCandidates() {
        val registry = registryOf(
            CapabilityId.FILES to CapabilityStatus.DISABLED,
            CapabilityId.SYSTEM to CapabilityStatus.MISSING_PERMISSION
        )
        val missing = CapabilityResolver.missing(registry, listOf("FILE_WRITE", "SYSTEM_INFO"))
        assertEquals(listOf("FILE_WRITE", "SYSTEM_INFO"), missing)
    }
}
