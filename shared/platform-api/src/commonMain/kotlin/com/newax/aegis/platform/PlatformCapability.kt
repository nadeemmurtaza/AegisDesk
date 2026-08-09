package com.newax.aegis.platform

/** A typed capability surface provided by one platform implementation. */
interface PlatformCapability {
    val id: CapabilityId

    /** Policy metadata; may be consulted without touching the underlying OS. */
    fun descriptor(): CapabilityDescriptor

    /** Current operational state; default is the descriptor's declared status. */
    fun status(): CapabilityStatus = descriptor().status
}
