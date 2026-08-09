package com.newax.aegis

import android.content.Context
import com.newax.aegis.platform.InMemoryPlatformCapabilityRegistry
import com.newax.aegis.platform.PlatformCapabilityRegistry
import com.newax.aegis.platform.android.AndroidPlatformCapabilities

/**
 * App-level holder for the platform capability registry, initialized once during
 * Application bootstrap (AegisApplication.onCreate).
 *
 * The Capabilities screen reads this to show capability status; the executor and
 * skill registry (later slices) consume the same instance, so there is exactly one
 * registry in the process. Seams (accessibility automation, screen capture,
 * permission requesting) are wired in a later slice — until then the capabilities
 * report their honest state (e.g. Desktop → UNAVAILABLE).
 */
object PlatformCapabilitiesHolder {

    @Volatile
    private var registry: PlatformCapabilityRegistry? = null

    /** Registers the Android capability surface once. Safe to call repeatedly. */
    fun init(context: Context) {
        if (registry != null) return
        registry = AndroidPlatformCapabilities.register(InMemoryPlatformCapabilityRegistry(), context)
    }

    /** The registered registry, or null before [init] has run. */
    fun registry(): PlatformCapabilityRegistry? = registry
}
