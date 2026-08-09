package com.newax.aegis.platform

/**
 * Operational state of a capability, reported by [PlatformCapability.status].
 *
 * Mirrors the named failure modes every capability must handle (AGENTS.md R9):
 * a permission the OS denies, a credential that is a reference and not present,
 * a user toggle that disabled it, or a platform that simply cannot provide it
 * (e.g. no desktop automation on iOS).
 */
enum class CapabilityStatus {
    READY,
    MISSING_PERMISSION,
    MISSING_CREDENTIAL,
    DISABLED,
    UNAVAILABLE,
    NOT_SUPPORTED;

    /** True only for [READY] — everything else blocks execution until resolved. */
    val isOperational: Boolean get() = this == READY
}
