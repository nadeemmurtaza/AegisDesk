package com.newax.aegis.platform.windows

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.isSuccess
import com.newax.aegis.platform.shell.ShellCommand
import com.newax.aegis.platform.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WindowsShellCapabilityTest {

    private val capability = WindowsShellCapability(baseDir = null)
    private val context = OperationContext.create("test", ActionOrigin.USER)

    private val javaBinary: String =
        File(System.getProperty("java.home"), "bin/java").absolutePath

    @Test
    fun runsARealCommandAndCapturesOutput() {
        val result = capability.run(
            ShellCommand(executable = javaBinary, args = listOf("-version")),
            context,
        )
        assertTrue("expected success, got: $result", result.isSuccess())
        val shell = (result as CapabilityResult.Success).value
        assertEquals(0, shell.exitCode)
        val output = shell.stdout + shell.stderr
        assertTrue("expected a version string, got: $output", output.contains("version"))
    }

    @Test
    fun missingExecutableFailsWithTypedResult() {
        val result = capability.run(
            ShellCommand(executable = "/definitely/not/a/real/executable-xyz"),
            context,
        )
        assertTrue(result is CapabilityResult.Failed)
    }

    @Test
    fun timeoutIsEnforcedAndForcesKill() {
        val result = capability.run(
            ShellCommand(executable = javaBinary, args = listOf("-version"), timeoutMs = 0L),
            context,
        )
        assertTrue("expected timeout failure, got: $result", result is CapabilityResult.Failed)
        assertTrue((result as CapabilityResult.Failed).error.contains("timed out"))
    }

    @Test
    fun nonzeroExitCodeIsReportedForFailingCommands() {
        val result = capability.run(
            ShellCommand(executable = javaBinary, args = listOf("-no-such-flag"), timeoutMs = 10_000L),
            context,
        )
        assertTrue(result.isSuccess())
        val shell: ShellResult = (result as CapabilityResult.Success).value
        assertTrue("expected nonzero exit code, got ${shell.exitCode}", shell.exitCode != 0)
    }

    @Test
    fun blankExecutableFailsFast() {
        val result = capability.run(ShellCommand(executable = "   "), context)
        assertTrue(result is CapabilityResult.Failed)
    }
}
