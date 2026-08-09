package com.newax.aegis.platform

/**
 * Dynamic capability registry. Lets a platform register and replace capability
 * implementations at runtime (e.g. an accessibility service coming online) and
 * lets the planner enumerate what is currently available without invoking it.
 */
interface PlatformCapabilityRegistry {
    /** Registers a capability; returns false if a capability with the same id exists. */
    fun register(capability: PlatformCapability): Boolean

    /** Removes the capability with the given id; returns false if none existed. */
    fun unregister(id: CapabilityId): Boolean

    fun get(id: CapabilityId): PlatformCapability?
    fun all(): List<PlatformCapability>
}
