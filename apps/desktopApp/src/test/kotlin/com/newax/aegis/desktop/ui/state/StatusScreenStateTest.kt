package com.newax.aegis.desktop.ui.state

import com.newax.aegis.model.FallbackModelProvider
import com.newax.aegis.model.ModelFormat
import com.newax.aegis.model.ModelState
import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.InMemoryPlatformCapabilityRegistry
import com.newax.aegis.platform.PlatformCapability
import com.newax.aegis.platform.PrivilegeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase B1 — the Status screen state holder: live snapshots of the capability
 * registry and the active model provider, all plain Kotlin with injectable
 * surfaces.
 */
class StatusScreenStateTest {

    @Test
    fun `capability rows are null before the registry initializes`() {
        val state = StatusScreenState(
            registry = { null },
            model = { FallbackModelProvider() },
        )

        assertNull(state.capabilityRows())
    }

    @Test
    fun `rows reflect the registered capabilities and their live status`() {
        val registry = InMemoryPlatformCapabilityRegistry()
        registry.register(FakeCapability(CapabilityId.DESKTOP, CapabilityStatus.READY))
        registry.register(FakeCapability(CapabilityId.SYSTEM, CapabilityStatus.NOT_SUPPORTED))
        val state = StatusScreenState(
            registry = { registry },
            model = { FallbackModelProvider() },
        )

        val rows = state.capabilityRows()!!
        assertEquals(2, rows.size)
        val desktop = rows.first { it.id == CapabilityId.DESKTOP }
        assertEquals(CapabilityStatus.READY, desktop.status)
        assertEquals(PrivilegeLevel.HIGH_IMPACT_SYSTEM, desktop.privilegeLevel)
        assertTrue(desktop.offline)
        val system = rows.first { it.id == CapabilityId.SYSTEM }
        assertEquals(CapabilityStatus.NOT_SUPPORTED, system.status)
    }

    @Test
    fun `model state and descriptor follow the active provider`() {
        val state = StatusScreenState(
            registry = { null },
            model = { FallbackModelProvider() },
        )

        assertEquals(ModelState.NOT_INSTALLED, state.modelState.value)
        assertEquals("Basic command engine", state.modelDescriptor.modelName)
        assertEquals(ModelFormat.UNKNOWN, state.modelDescriptor.format)
        assertEquals("", state.modelDescriptor.sha256)
    }

    private class FakeCapability(
        override val id: CapabilityId,
        private val status: CapabilityStatus,
    ) : PlatformCapability {
        override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
            id = id,
            version = 1,
            displayName = id.name,
            description = "fake capability for status screen tests",
            privilegeLevel = if (id == CapabilityId.DESKTOP) {
                PrivilegeLevel.HIGH_IMPACT_SYSTEM
            } else {
                PrivilegeLevel.READ_ONLY
            },
            status = status,
        )
    }
}
