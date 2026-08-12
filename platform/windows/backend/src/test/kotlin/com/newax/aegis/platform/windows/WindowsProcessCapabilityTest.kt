package com.newax.aegis.platform.windows

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.isSuccess
import com.newax.aegis.platform.processes.ProcessSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WindowsProcessCapabilityTest {

    private val capability = WindowsProcessCapability()
    private val context = OperationContext.create("test", ActionOrigin.USER)

    private val IS_WINDOWS: Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private val javaBinary: String =
        File(System.getProperty("java.home"), "bin/java").absolutePath

    @Test
    fun launchStartsARealProcessWithItsPid() {
        val result = capability.launch(javaBinary, listOf("-version"), null, context)
        assertTrue("expected launch success, got $result", result.isSuccess())
        val ref = (result as CapabilityResult.Success).value
        assertTrue("expected a real pid, got ${ref.pid}", ref.pid > 0)
        assertNotNull(ref.name)
    }

    @Test
    fun blankExecutableFailsFast() {
        assertTrue(capability.launch("   ", emptyList(), null, context) is CapabilityResult.Failed)
    }

    @Test
    fun nonWindowsReportsNotSupportedAndTypedFailures() {
        assumeTrue("behaviour under test only applies off-Windows", !IS_WINDOWS)
        assertEquals(CapabilityStatus.NOT_SUPPORTED, capability.status())
        assertTrue(capability.list() is CapabilityResult.Failed)
        assertTrue(capability.info(com.newax.aegis.platform.processes.ProcessRef(pid = 1L)) is CapabilityResult.Failed)
        assertTrue(capability.signal(com.newax.aegis.platform.processes.ProcessRef(pid = 1L), ProcessSignal.KILL, context) is CapabilityResult.Failed)
    }

    @Test
    fun windowsListsEveryProcess() {
        assumeTrue("process listing requires Windows", IS_WINDOWS)
        val result = capability.list()
        assertTrue("expected listing success, got $result", result.isSuccess())
        val processes = (result as CapabilityResult.Success).value
        assertTrue("expected at least the current process, got ${processes.size}", processes.isNotEmpty())
        assertTrue(processes.any { it.pid == ProcessHandle.current().pid() })
    }
}
