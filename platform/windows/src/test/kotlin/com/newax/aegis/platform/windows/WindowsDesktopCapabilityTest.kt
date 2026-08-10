package com.newax.aegis.platform.windows

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.desktop.UiTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class WindowsDesktopCapabilityTest {

    private val capability = WindowsDesktopCapability()
    private val context = OperationContext.create("test", ActionOrigin.USER)

    private val IS_WINDOWS: Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    @Test
    fun nonWindowsReportsNotSupportedAndTypedFailures() {
        assumeTrue("behaviour under test only applies off-Windows", !IS_WINDOWS)
        assertEquals(CapabilityStatus.NOT_SUPPORTED, capability.status())
        assertTrue(capability.listWindows() is CapabilityResult.Failed)
        assertTrue(capability.screenshot() is CapabilityResult.Failed)
        assertTrue(capability.waitFor(UiTarget.Semantic("anything"), 0L) is CapabilityResult.Failed)
        assertTrue(capability.click(UiTarget.Coordinates(100f, 100f), context) is CapabilityResult.Failed)
    }

    @Test
    fun semanticTargetsFailHonestlyUntilTheUiaBridgeExists() {
        assumeTrue("semantic target handling only applies on Windows", IS_WINDOWS)
        val click = capability.click(UiTarget.Semantic("any button"), context)
        assertTrue("expected a typed failure, got $click", click is CapabilityResult.Failed)
        assertTrue((click as CapabilityResult.Failed).error.contains("UI Automation"))
    }
}
