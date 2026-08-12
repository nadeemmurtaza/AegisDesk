package com.newax.aegis.platform.windows

import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.platform.system.ConnectivityState
import com.newax.aegis.platform.system.SystemCapability
import com.newax.aegis.platform.system.SystemInfo
import java.awt.Desktop
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * System capability on Windows.
 *
 * [info] and [connectivity] are pure JVM (device properties, a bounded TCP probe
 * to a well-known internet address with a LAN fallback). [batteryPercent] queries
 * WMI through a fixed `powershell.exe` one-liner — a typed, constant command with
 * no user input, read-only, and returns null honestly when no battery exists
 * (desktops). [openSettings] opens the Windows Settings app via `ms-settings:`
 * URIs through [Desktop.browse]; the section is sanitized to `[a-z0-9-]` before
 * the URI is built (R12: untrusted input is data, never instruction).
 *
 * Windows has no Android-style runtime-permission gate for the surfaces this
 * capability exposes — a third-party process either can perform them (user
 * context) or fails with the OS's own error — so [requestPermission] is a
 * no-op success and that semantic is documented here rather than pretended.
 */
class WindowsSystemCapability : SystemCapability {

    override val id: CapabilityId get() = CapabilityId.SYSTEM

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "System",
        description = "Device info, connectivity, battery, settings navigation",
        privilegeLevel = PrivilegeLevel.READ_ONLY,
    )

    override fun info(): CapabilityResult<SystemInfo> {
        val osName = System.getProperty("os.name") ?: "Unknown"
        val osVersion = listOfNotNull(System.getProperty("os.version"), System.getProperty("os.arch"))
            .joinToString(" ")
            .ifBlank { "unknown" }
        val totalMemory = try {
            val bean = ManagementFactory.getOperatingSystemMXBean()
            if (bean is com.sun.management.OperatingSystemMXBean) bean.totalMemorySize else null
        } catch (e: Exception) {
            null
        }
        return CapabilityResult.Success(
            SystemInfo(
                osName = osName,
                osVersion = osVersion,
                deviceModel = System.getenv("COMPUTERNAME")?.ifBlank { null },
                locale = Locale.getDefault().toLanguageTag(),
                timezone = TimeZone.getDefault().id,
                totalMemoryBytes = totalMemory,
            )
        )
    }

    override fun connectivity(): CapabilityResult<ConnectivityState> {
        val internetReachable = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("1.1.1.1", 53), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
        if (internetReachable) return CapabilityResult.Success(ConnectivityState.ONLINE)
        val anyInterfaceUp = try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.any { it.isUp && !it.isLoopback } ?: false
        } catch (e: Exception) {
            false
        }
        return CapabilityResult.Success(
            if (anyInterfaceUp) ConnectivityState.LIMITED else ConnectivityState.OFFLINE
        )
    }

    override fun batteryPercent(): CapabilityResult<Int?> {
        if (!isWindows()) return CapabilityResult.Success(null)
        return try {
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "(Get-CimInstance Win32_Battery).EstimatedChargeRemaining",
            ).start()
            if (!process.waitFor(BATTERY_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return CapabilityResult.Success(null)
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            if (output.isEmpty()) {
                // No Win32_Battery instance — desktop machine, not a fault.
                CapabilityResult.Success(null)
            } else {
                CapabilityResult.Success(output.toIntOrNull()?.coerceIn(0, 100))
            }
        } catch (e: Exception) {
            CapabilityResult.Success(null)
        }
    }

    override fun requestPermission(permission: String, context: OperationContext): CapabilityResult<Unit> =
        // No runtime-permission gate on Windows for these surfaces; see class KDoc.
        CapabilityResult.Success(Unit)

    override fun openSettings(section: String?, context: OperationContext): CapabilityResult<Unit> {
        if (!isWindows()) return CapabilityResult.Failed("Windows settings navigation requires Windows")
        // Whitelist the section charset so a caller-supplied value can never escape
        // the ms-settings: URI (R12). Empty/null opens the Settings home page.
        val sanitized = section.orEmpty().lowercase().filter { it.isLetterOrDigit() || it == '-' }
        return try {
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return CapabilityResult.Failed("opening settings is not supported in this environment")
            }
            desktop.browse(URI("ms-settings:$sanitized"))
            CapabilityResult.Success(Unit)
        } catch (e: Exception) {
            CapabilityResult.Failed("cannot open settings: ${e.message}")
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2500
        const val BATTERY_QUERY_TIMEOUT_MS = 10_000L
    }
}
