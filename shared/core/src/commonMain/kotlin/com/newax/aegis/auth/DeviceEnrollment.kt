package com.newax.aegis.auth

/**
 * The device half of authentication (docs/AUTH_DESIGN.md §3).
 *
 * A device is not authenticated by logging in. It is authenticated by **being
 * enrolled** — holding an Ed25519 identity (`shared/sync` `Identity`) that a
 * already-trusted device admitted over the SAS-verified pairing channel, plus
 * hardware custody of the profile's wrapped key.
 *
 * This is why there is no password to phish and no server to breach: adding a
 * device is a physical act between two devices the owner holds, and revoking one
 * is a key operation rather than a database flag.
 */
data class DeviceEnrollment(
    /** Stable device identifier — the `shared/sync` `DeviceIdentity.deviceId`. */
    val deviceId: String,
    val profile: ProfileRef,
    val displayName: String,
    /** Custody tier this device provided **at enrollment time**. */
    val enrolledTier: CustodyTier,
    val enrolledAtMs: Long,
    val revokedAtMs: Long? = null,
) {
    init {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(enrolledTier != CustodyTier.NONE) {
            "a device with no key custody cannot be enrolled — refuse it instead"
        }
    }

    val isActive: Boolean get() = revokedAtMs == null
}

/** Why an enrollment was refused. Every one is shown to the user, never silent. */
enum class EnrollmentRefusal {
    /** The device's custody tier is below what the profile requires. */
    INSUFFICIENT_CUSTODY,

    /** No key custody at all — nowhere safe to put the wrapped key. */
    NO_CUSTODY,

    /** This device is already enrolled for this profile. */
    ALREADY_ENROLLED,

    /** The device was revoked; re-enrollment needs a fresh pairing, not a retry. */
    REVOKED,
}

sealed interface EnrollmentDecision {
    data object Allowed : EnrollmentDecision
    data class Refused(val reason: EnrollmentRefusal, val explanation: String) : EnrollmentDecision
}

/**
 * Decides whether a device may hold a profile.
 *
 * Deliberately pure and platform-free so the rule is testable without a device:
 * the interesting cases are refusals, and refusals that only manifest on real
 * hardware are refusals nobody tests.
 */
object EnrollmentGuard {

    /**
     * A profile requiring [requiredTier] may only be enrolled on a device whose
     * custody is at least as strong.
     *
     * Tiers are compared by their declaration order in [CustodyTier], strongest
     * first — hence the inverted comparison.
     */
    fun evaluate(
        deviceTier: CustodyTier,
        requiredTier: CustodyTier,
        alreadyEnrolled: DeviceEnrollment?,
    ): EnrollmentDecision {
        if (alreadyEnrolled != null) {
            return if (alreadyEnrolled.isActive) {
                EnrollmentDecision.Refused(
                    EnrollmentRefusal.ALREADY_ENROLLED,
                    "This device already holds the profile.",
                )
            } else {
                EnrollmentDecision.Refused(
                    EnrollmentRefusal.REVOKED,
                    "This device was removed from the profile. Pair it again to re-add it.",
                )
            }
        }
        if (deviceTier == CustodyTier.NONE) {
            return EnrollmentDecision.Refused(
                EnrollmentRefusal.NO_CUSTODY,
                "This device has no secure place to keep the profile's key.",
            )
        }
        if (deviceTier.ordinal > requiredTier.ordinal) {
            return EnrollmentDecision.Refused(
                EnrollmentRefusal.INSUFFICIENT_CUSTODY,
                "This profile requires ${requiredTier.label} key protection; " +
                    "this device offers ${deviceTier.label}.",
            )
        }
        return EnrollmentDecision.Allowed
    }
}

/** Human-readable tier name for refusal messages and the device list. */
val CustodyTier.label: String
    get() = when (this) {
        CustodyTier.HARDWARE_ISOLATED -> "dedicated security chip"
        CustodyTier.HARDWARE -> "hardware-backed"
        CustodyTier.SOFTWARE -> "software-only"
        CustodyTier.NONE -> "none"
    }
