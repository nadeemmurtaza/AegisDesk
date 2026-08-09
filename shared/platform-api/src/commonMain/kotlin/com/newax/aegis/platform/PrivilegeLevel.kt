package com.newax.aegis.platform

/**
 * How impactful an operation is, as declared by the capability contract.
 *
 * Maps to policy modes (ARCHITECTURE.md corollary):
 * READ_ONLY → AUTO, STANDARD → CONFIGURABLE, HIGH_IMPACT_SYSTEM → APPROVAL,
 * CRITICAL → STRONG CONFIRMATION. The mapping itself is user-controllable;
 * this enum is only the per-operation declaration the policy engine reads.
 */
enum class PrivilegeLevel {
    READ_ONLY,
    STANDARD,
    HIGH_IMPACT_SYSTEM,
    CRITICAL
}
