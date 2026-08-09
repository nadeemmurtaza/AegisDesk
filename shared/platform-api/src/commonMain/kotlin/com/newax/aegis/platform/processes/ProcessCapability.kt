package com.newax.aegis.platform.processes

import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PlatformCapability

data class ProcessRef(val pid: Long, val name: String? = null)

data class ProcessInfo(
    val pid: Long,
    val name: String,
    val commandLine: String? = null,
    val cpuPercent: Double? = null,
    val memoryBytes: Long? = null,
)

enum class ProcessSignal { TERMINATE, KILL, PAUSE, RESUME }

/**
 * Process inspection and control. Launching and signalling processes are privileged
 * operations and require [OperationContext]; listing and reading status do not.
 */
interface ProcessCapability : PlatformCapability {
    override val id: CapabilityId get() = CapabilityId.PROCESSES

    fun launch(
        executable: String,
        args: List<String> = emptyList(),
        workingDirectory: String? = null,
        context: OperationContext,
    ): CapabilityResult<ProcessRef>

    fun list(): CapabilityResult<List<ProcessInfo>>
    fun info(ref: ProcessRef): CapabilityResult<ProcessInfo>
    fun signal(ref: ProcessRef, signal: ProcessSignal, context: OperationContext): CapabilityResult<Unit>
}
