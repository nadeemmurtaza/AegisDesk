package com.newax.aegis.platform

/**
 * Static, introspectable metadata about a capability — the policy entry the
 * planner and authority spine consult *without* invoking the capability
 * (AGENTS.md R6: a new tool gets its descriptor + policy entry in the same change).
 */
data class CapabilityDescriptor(
    val id: CapabilityId,
    val version: Int,
    val displayName: String,
    val description: String,
    val privilegeLevel: PrivilegeLevel,
    /** OS-level permission (e.g. an Android manifest permission) the capability needs. */
    val requiredPermission: String? = null,
    /** Credential key the capability needs, as a reference — never a value (invariant 4). */
    val requiredCredentialKey: String? = null,
    /** 90/10 rule: true when the capability works offline (AGENTS.md invariant 8). */
    val offline: Boolean = true,
    val status: CapabilityStatus = CapabilityStatus.READY,
)
