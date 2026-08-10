package com.newax.aegis.desktop

import com.newax.aegis.platform.InMemoryPlatformCapabilityRegistry
import com.newax.aegis.platform.PlatformCapabilityRegistry
import com.newax.aegis.platform.windows.WindowsDesktopCapability

/**
 * Desktop counterpart of Android's [com.newax.aegis.PlatformCapabilitiesHolder]:
 * the one platform-capability registry in the desktop process, registered once
 * during runner bootstrap ([init]).
 *
 * Currently the desktop surface is the Windows automation adapter
 * ([WindowsDesktopCapability]) — registered unconditionally, but reporting
 * [com.newax.aegis.platform.CapabilityStatus.NOT_SUPPORTED] on non-Windows OSes
 * (the honest platform state, not a stub). Files/processes/shell/secrets/system
 * adapters arrive in later platform slices and register here the same way.
 *
 * The planner and the executor (Phase 5h) consume this same instance, so there
 * is exactly one registry per process — the same invariant the Android app holds.
 */
object DesktopCapabilitiesHolder {

    private val lock = Any()

    @Volatile
    private var registry: PlatformCapabilityRegistry? = null

    /** Registers the desktop capability surface once. Safe to call repeatedly. */
    fun init() {
        synchronized(lock) {
            if (registry != null) return
            val registry = InMemoryPlatformCapabilityRegistry()
            registry.register(WindowsDesktopCapability())
            this.registry = registry
        }
    }

    /** The registered registry, or null before [init] has run. */
    fun registry(): PlatformCapabilityRegistry? = registry
}
