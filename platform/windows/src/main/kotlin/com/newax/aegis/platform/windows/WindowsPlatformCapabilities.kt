package com.newax.aegis.platform.windows

import com.newax.aegis.platform.PlatformCapabilities
import com.newax.aegis.platform.PlatformCapabilityRegistry
import com.newax.aegis.platform.desktop.DesktopCapability
import com.newax.aegis.platform.files.FileCapability
import com.newax.aegis.platform.processes.ProcessCapability
import com.newax.aegis.platform.secrets.SecretsCapability
import com.newax.aegis.platform.shell.ShellCapability
import com.newax.aegis.platform.system.SystemCapability
import java.io.File

/**
 * The Windows implementation of [PlatformCapabilities], constructed via [create]
 * and registered into the app's capability registry by [register] — the desktop
 * app bootstrap's single entry point (the Android equivalent is
 * AndroidPlatformCapabilities in platform/android).
 *
 * [baseDir] confines every [WindowsFileCapability] path and every bounded shell
 * run inside one directory when provided (the safe default for the desktop app);
 * pass null for unrestricted real-file access. [secretsDir] is where the DPAPI
 * vault lives (default `~/.aegis`).
 */
class WindowsPlatformCapabilities(
    override val files: FileCapability,
    override val processes: ProcessCapability,
    override val shell: ShellCapability,
    override val desktop: DesktopCapability,
    override val secrets: SecretsCapability,
    override val system: SystemCapability,
) : PlatformCapabilities {

    companion object {

        /** Builds the full Windows capability surface. */
        fun create(
            baseDir: File? = null,
            secretsDir: File = File(System.getProperty("user.home") ?: ".", ".aegis"),
        ): WindowsPlatformCapabilities = WindowsPlatformCapabilities(
            files = WindowsFileCapability(baseDir),
            processes = WindowsProcessCapability(),
            shell = WindowsShellCapability(baseDir),
            desktop = WindowsDesktopCapability(),
            secrets = WindowsSecretsCapability(secretsDir),
            system = WindowsSystemCapability(),
        )

        /** Builds the surface and registers every capability into [registry]. */
        fun register(
            registry: PlatformCapabilityRegistry,
            baseDir: File? = null,
            secretsDir: File = File(System.getProperty("user.home") ?: ".", ".aegis"),
        ): PlatformCapabilityRegistry {
            create(baseDir, secretsDir).all.forEach { registry.register(it) }
            return registry
        }
    }
}
