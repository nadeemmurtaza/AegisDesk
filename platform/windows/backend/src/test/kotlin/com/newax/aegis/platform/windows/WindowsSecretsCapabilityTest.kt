package com.newax.aegis.platform.windows

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.getOrNull
import com.newax.aegis.platform.isSuccess
import com.newax.aegis.platform.secrets.SecretAvailability
import com.newax.aegis.platform.secrets.SecretSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WindowsSecretsCapabilityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = OperationContext.create("test", ActionOrigin.USER)

    private val IS_WINDOWS: Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    @Test
    fun dpapiStoreReadAvailabilityAndDeleteRoundTrip() {
        assumeTrue("DPAPI round-trip requires Windows", IS_WINDOWS)
        val capability = WindowsSecretsCapability(temp.newFolder("vault"))

        assertTrue(capability.store("GITHUB_TOKEN", "ghp_super_secret", context).isSuccess())
        assertEquals(
            SecretAvailability("GITHUB_TOKEN", available = true, source = SecretSource.OS_VAULT),
            (capability.availability("GITHUB_TOKEN") as CapabilityResult.Success).value,
        )
        assertFalse(capability.availability("MISSING_KEY").getOrNull()?.available ?: true)

        val raw = capability.read("GITHUB_TOKEN", context)
        assertEquals("ghp_super_secret", (raw as CapabilityResult.Success).value)
        assertEquals(listOf("GITHUB_TOKEN"), capability.listKeys().getOrNull())

        assertTrue(capability.delete("GITHUB_TOKEN", context).isSuccess())
        assertTrue(capability.read("GITHUB_TOKEN", context) is CapabilityResult.Failed)
        assertTrue(capability.availability("GITHUB_TOKEN").getOrNull()?.available == false)
    }

    @Test
    fun readingAbsentKeyFails() {
        assumeTrue("DPAPI round-trip requires Windows", IS_WINDOWS)
        val capability = WindowsSecretsCapability(temp.newFolder("vault"))
        assertTrue(capability.read("absent", context) is CapabilityResult.Failed)
    }

    @Test
    fun nonWindowsReportsNotSupportedAndTypedFailures() {
        assumeTrue("behaviour under test only applies off-Windows", !IS_WINDOWS)
        val capability = WindowsSecretsCapability(temp.newFolder("vault"))
        assertEquals(CapabilityStatus.NOT_SUPPORTED, capability.status())
        assertTrue(capability.store("k", "v", context) is CapabilityResult.Failed)
        assertTrue(capability.read("k", context) is CapabilityResult.Failed)
        assertTrue(capability.delete("k", context) is CapabilityResult.Failed)
        assertTrue(capability.listKeys() is CapabilityResult.Failed)
        assertTrue(capability.availability("k") is CapabilityResult.Failed)
    }
}
