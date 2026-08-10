package com.newax.aegis

import com.newax.aegis.platform.InMemoryPlatformCapabilityRegistry
import com.newax.aegis.platform.windows.WindowsPlatformCapabilities

/**
 * Aegis Desktop bootstrap (Track A wiring): builds the Windows platform capability
 * surface and registers it into the process-wide registry, then prints each
 * capability's operational status. The Compose Desktop UI (Track B) will consume
 * this same registry — exactly one registry per process, mirroring the Android
 * app's PlatformCapabilitiesHolder.
 */
fun main() {
    val registry = InMemoryPlatformCapabilityRegistry()
    WindowsPlatformCapabilities.register(registry)
    println("Aegis Desktop bootstrap OK — platform capabilities:")
    registry.all()
        .sortedBy { it.id.name }
        .forEach { capability ->
            val descriptor = capability.descriptor()
            println(
                "  ${descriptor.id.name.padEnd(9)} v${descriptor.version}  " +
                    "${capability.status().name.padEnd(18)}  " +
                    "${descriptor.privilegeLevel.name.padEnd(19)}  ${descriptor.description}"
            )
        }
}
