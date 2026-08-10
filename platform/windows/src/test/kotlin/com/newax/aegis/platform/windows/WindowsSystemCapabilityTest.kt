package com.newax.aegis.platform.windows

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.isSuccess
import com.newax.aegis.platform.system.ConnectivityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class WindowsSystemCapabilityTest {

    private val capability = WindowsSystemCapability()
    private val context = OperationContext.create("test", ActionOrigin.USER)

    private val IS_WINDOWS: Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    @Test
    fun infoReportsRealPlatformValues() {
        val info = (capability.info() as CapabilityResult.Success).value
        assertFalse(info.osName.isBlank())
        assertFalse(info.osVersion.isBlank())
        assertFalse(info.locale.isBlank())
        assertNotNull(info.timezone)
        assertEquals(System.getProperty("os.name"), info.osName)
    }

    @Test
    fun connectivityAlwaysResolvesToAState() {
        val result = capability.connectivity()
        assertTrue("expected success, got $result", result.isSuccess())
        val state = (result as CapabilityResult.Success).value
        assertTrue(
            "unexpected state $state",
            state == ConnectivityState.ONLINE || state == ConnectivityState.OFFLINE || state == ConnectivityState.LIMITED,
        )
    }

    @Test
    fun nonWindowsHasNoBattery() {
        assumeTrue("battery path under test only applies off-Windows", !IS_WINDOWS)
        val result = capability.batteryPercent()
        assertTrue("expected success with null, got $result", result.isSuccess())
        assertEquals(null, (result as CapabilityResult.Success).value)
    }

    @Test
    fun requestPermissionIsAWindowsNoOp() {
        // Windows has no runtime-permission gate for these surfaces — see class KDoc.
        assertTrue(capability.requestPermission("anything", context).isSuccess())
    }

    @Test
    fun nonWindowsSettingsNavigationFailsHonestly() {
        assumeTrue("settings path under test only applies off-Windows", !IS_WINDOWS)
        assertTrue(capability.openSettings("display", context) is CapabilityResult.Failed)
    }
}
