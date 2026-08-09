package com.newax.aegis.platform

/**
 * Default [PlatformCapabilityRegistry] backed by an in-memory map.
 *
 * Not thread-safe by design: registration happens once during bootstrap, before
 * the executor starts. Replacing a capability at runtime (e.g. accessibility
 * coming online) is a single-threaded startup event on this repo's runtime model.
 */
class InMemoryPlatformCapabilityRegistry : PlatformCapabilityRegistry {

    private val store = mutableMapOf<CapabilityId, PlatformCapability>()

    override fun register(capability: PlatformCapability): Boolean {
        if (store.containsKey(capability.id)) return false
        store[capability.id] = capability
        return true
    }

    override fun unregister(id: CapabilityId): Boolean = store.remove(id) != null

    override fun get(id: CapabilityId): PlatformCapability? = store[id]

    override fun all(): List<PlatformCapability> = store.values.toList()
}
