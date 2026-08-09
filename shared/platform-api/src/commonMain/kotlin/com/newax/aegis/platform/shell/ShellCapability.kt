package com.newax.aegis.platform.shell

import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PlatformCapability

/**
 * A *bounded* shell runner — the only place a command string becomes a process
 * (ARCHITECTURE.md: genuine terminal commands go behind a bounded shell runner with
 * policy + audit; R11: exactly one path to exec, and the guard lives inside it).
 * The model layer never reaches this directly; it goes through typed actions and
 * an [OperationContext]. Timeout and output caps make runaway commands impossible.
 */
data class ShellCommand(
    val executable: String,
    val args: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 30_000L,
    val maxOutputBytes: Int = 64 * 1024,
)

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

interface ShellCapability : PlatformCapability {
    override val id: CapabilityId get() = CapabilityId.SHELL

    fun run(command: ShellCommand, context: OperationContext): CapabilityResult<ShellResult>
}
