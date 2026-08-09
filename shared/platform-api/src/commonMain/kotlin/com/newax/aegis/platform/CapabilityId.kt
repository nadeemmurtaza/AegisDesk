package com.newax.aegis.platform

/**
 * Canonical, stable identifier of a platform capability. The planner, skill
 * registry, and policy engine reference capabilities by these ids, never by
 * free-form strings (ARCHITECTURE.md: typed actions, not raw command text).
 *
 * The first six are implemented by the platform adapters; the rest are reserved
 * ids so capability references stay stable as new surfaces land.
 */
enum class CapabilityId {
    FILES,
    PROCESSES,
    SHELL,
    DESKTOP,
    SECRETS,
    SYSTEM,
    NETWORK,
    CLIPBOARD,
    NOTIFICATIONS,
    CONTACTS,
    CALENDAR,
    SMS,
    VOICE,
    VISION,
    DEVICE
}
