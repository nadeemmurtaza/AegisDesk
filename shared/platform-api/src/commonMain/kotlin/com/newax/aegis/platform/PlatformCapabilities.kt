package com.newax.aegis.platform

import com.newax.aegis.platform.desktop.DesktopCapability
import com.newax.aegis.platform.files.FileCapability
import com.newax.aegis.platform.processes.ProcessCapability
import com.newax.aegis.platform.secrets.SecretsCapability
import com.newax.aegis.platform.shell.ShellCapability
import com.newax.aegis.platform.system.SystemCapability

/**
 * The aggregate capability surface a platform exposes to the shared runtime —
 * one typed object per platform body (Android, Windows, macOS, Linux, iOS).
 * Implemented by the platform adapters (platform/android, platform/windows);
 * consumed by the executor, skill registry, and planner.
 */
interface PlatformCapabilities {
    val files: FileCapability
    val processes: ProcessCapability
    val shell: ShellCapability
    val desktop: DesktopCapability
    val secrets: SecretsCapability
    val system: SystemCapability

    /** All capabilities on this surface. */
    val all: List<PlatformCapability>
        get() = listOf(files, processes, shell, desktop, secrets, system)

    /** Capability by [CapabilityId], typed on the caller side. */
    fun <T : PlatformCapability> byId(id: CapabilityId): T? =
        all.firstOrNull { it.id == id } as? T
}
