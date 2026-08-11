package com.newax.aegis.platform.windows

import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.InMemoryPlatformCapabilityRegistry
import com.newax.aegis.platform.PlatformCapabilities
import com.newax.aegis.platform.PlatformCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WindowsPlatformCapabilitiesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun createBuildsTheFullSurface() {
        val capabilities: PlatformCapabilities = WindowsPlatformCapabilities.create(
            baseDir = temp.newFolder("files"),
            secretsDir = temp.newFolder("vault"),
        )
        assertTrue(capabilities.files is WindowsFileCapability)
        assertTrue(capabilities.processes is WindowsProcessCapability)
        assertTrue(capabilities.shell is WindowsShellCapability)
        assertTrue(capabilities.desktop is WindowsDesktopCapability)
        assertTrue(capabilities.secrets is WindowsSecretsCapability)
        assertTrue(capabilities.system is WindowsSystemCapability)
        assertEquals(6, capabilities.all.size)
    }

    @Test
    fun byIdResolvesEveryRegisteredCapability() {
        val capabilities = WindowsPlatformCapabilities.create(
            baseDir = temp.newFolder("files"),
            secretsDir = temp.newFolder("vault"),
        )
        CapabilityId.entries.take(6).forEach { id ->
            assertNotNull("expected a capability for $id", capabilities.byId<PlatformCapability>(id))
        }
        assertNull(capabilities.byId<PlatformCapability>(CapabilityId.NETWORK))
    }

    @Test
    fun registerPopulatesTheRegistryWithAllSixCapabilities() {
        val registry = InMemoryPlatformCapabilityRegistry()
        WindowsPlatformCapabilities.register(
            registry,
            baseDir = temp.newFolder("files"),
            secretsDir = temp.newFolder("vault"),
        )
        assertEquals(6, registry.all().size)
        CapabilityId.entries.take(6).forEach { id ->
            assertNotNull("expected $id registered", registry.get(id))
        }
        assertNull(registry.get(CapabilityId.NETWORK))
    }
}
