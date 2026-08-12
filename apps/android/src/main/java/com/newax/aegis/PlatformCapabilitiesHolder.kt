package com.newax.aegis

import android.content.Context
import com.newax.aegis.platform.InMemoryPlatformCapabilityRegistry
import com.newax.aegis.platform.PlatformCapabilityRegistry
import com.newax.aegis.platform.android.AndroidDesktopCapability
import com.newax.aegis.platform.android.AndroidPlatformCapabilities
import com.newax.aegis.platform.android.SemanticAutomation

/**
 * App-level holder for the platform capability registry, initialized once during
 * Application bootstrap (NewaxApplication.onCreate).
 *
 * The Capabilities screen reads this to show capability status; the executor and
 * skill registry (later slices) consume the same instance, so there is exactly one
 * registry in the process.
 *
 * The Desktop capability's [SemanticAutomation] bridge is attached at runtime: the
 * app's NewaxAccessibilityService calls [attachAutomation] when it connects and
 * [detachAutomation] when it is destroyed. Ordering is safe either way — a service
 * that connects before bootstrap is replayed into the capability at [init].
 */
object PlatformCapabilitiesHolder {

    private val lock = Any()

    @Volatile
    private var registry: PlatformCapabilityRegistry? = null

    @Volatile
    private var desktop: AndroidDesktopCapability? = null

    @Volatile
    private var pendingAutomation: SemanticAutomation? = null

    /** Registers the Android capability surface once. Safe to call repeatedly. */
    fun init(context: Context) {
        synchronized(lock) {
            if (registry != null) return
            val capabilities = AndroidPlatformCapabilities.create(context, automation = pendingAutomation)
            desktop = capabilities.desktop as? AndroidDesktopCapability
            val registry = InMemoryPlatformCapabilityRegistry()
            capabilities.all.forEach { registry.register(it) }
            this.registry = registry
        }
    }

    /** Called by NewaxAccessibilityService.onServiceConnected. */
    fun attachAutomation(automation: SemanticAutomation) {
        synchronized(lock) {
            pendingAutomation = automation
            desktop?.attach(automation)
        }
    }

    /** Called by NewaxAccessibilityService.onDestroy. */
    fun detachAutomation() {
        synchronized(lock) {
            pendingAutomation = null
            desktop?.detach()
        }
    }

    /** The registered registry, or null before [init] has run. */
    fun registry(): PlatformCapabilityRegistry? = registry

    /**
     * Test seam — installs a fake registry so the planner/executor resolution
     * paths are unit-testable without an Android [Context] (pure JVM tests
     * cannot build [AndroidPlatformCapabilities]). Production code never calls
     * this: [init] remains the only real path, and this seam does not touch the
     * desktop capability or automation state.
     */
    fun setRegistryForTest(registry: PlatformCapabilityRegistry?) {
        synchronized(lock) {
            this.registry = registry
        }
    }
}
