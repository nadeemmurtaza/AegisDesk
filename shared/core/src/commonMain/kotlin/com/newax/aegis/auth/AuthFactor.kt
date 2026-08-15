package com.newax.aegis.auth

/**
 * The authentication model (docs/AUTH_DESIGN.md).
 *
 * Two independent halves, and neither alone opens a profile:
 *
 *  - **Device** ([AuthFactorKind.DEVICE_ENROLLMENT]) — possession. The device
 *    holds an enrolled Ed25519 identity for this profile (`shared/sync`
 *    `Identity`), and hardware custody of the wrapping key.
 *  - **User** (everything else) — knowledge or inherence. The person at the
 *    device proves they are the owner.
 *
 * There is no server. Nothing here asks a remote party whether a credential is
 * correct, because there is nobody to ask — see AUTH_DESIGN.md §2.
 */
enum class AuthFactorKind {
    /** This device is enrolled for the profile and holds its wrapped key. */
    DEVICE_ENROLLMENT,

    /** A passphrase run through a memory-hard KDF to derive key material. */
    PASSPHRASE,

    /** Class 3 biometric, bound to a hardware key via `CryptoObject`. */
    BIOMETRIC_STRONG,

    /** Class 2 biometric. Spoofable by design; cannot reach [AuthStrength.HARDWARE_BOUND]. */
    BIOMETRIC_WEAK,

    /** Device PIN/pattern/password. Proves device access, not identity. */
    DEVICE_CREDENTIAL,

    /** Time-based one-time code. A second factor, never a first. */
    TOTP,

    /** Speaker verification. Replay-spoofable — a convenience signal only. */
    VOICE,
}

/**
 * What an authentication attempt actually established, in ascending order.
 *
 * The ordering is the point: [satisfies] is the only comparison callers make,
 * and it is `>=`. A rung is not "a nicer prompt" — each one is a different
 * claim about the world.
 */
enum class AuthStrength {
    /** Nothing was established. */
    NONE,

    /**
     * A human is present and acted deliberately — they tapped Approve. This is
     * *not* an identity claim: anyone holding an unlocked device can produce it.
     */
    PRESENCE,

    /**
     * A user factor was checked in software. Identity is claimed, but the check
     * itself is only as good as the process that ran it.
     */
    VERIFIED,

    /**
     * A hardware-held key was unlocked by a Class 3 factor and is available for
     * use. The action is *impossible* without it rather than merely discouraged
     * — which is the distinction ENGINEERING.md §B5 exists to make.
     */
    HARDWARE_BOUND,
}

/**
 * The ceiling a factor can reach on its own, however well it is implemented.
 *
 * These caps are not tunable. [AuthFactorKind.VOICE] is capped at [AuthStrength.PRESENCE]
 * because speaker embeddings are replay-spoofable, and [AuthFactorKind.BIOMETRIC_WEAK]
 * and [AuthFactorKind.DEVICE_CREDENTIAL] stop at [AuthStrength.VERIFIED] because
 * Android does not let them gate a `setUserAuthenticationRequired` key with
 * Class 3 semantics. Raising either would be a lie told in an enum.
 */
val AuthFactorKind.maxStrength: AuthStrength
    get() = when (this) {
        AuthFactorKind.DEVICE_ENROLLMENT -> AuthStrength.PRESENCE
        AuthFactorKind.PASSPHRASE -> AuthStrength.VERIFIED
        AuthFactorKind.BIOMETRIC_STRONG -> AuthStrength.HARDWARE_BOUND
        AuthFactorKind.BIOMETRIC_WEAK -> AuthStrength.VERIFIED
        AuthFactorKind.DEVICE_CREDENTIAL -> AuthStrength.VERIFIED
        AuthFactorKind.TOTP -> AuthStrength.VERIFIED
        AuthFactorKind.VOICE -> AuthStrength.PRESENCE
    }

/** True when [this] meets or exceeds [required]. The only comparison callers should make. */
fun AuthStrength.satisfies(required: AuthStrength): Boolean = ordinal >= required.ordinal

/**
 * The strength a set of satisfied factors establishes together.
 *
 * Combination does **not** add: two [AuthStrength.VERIFIED] factors are still
 * [AuthStrength.VERIFIED]. Stacking software checks does not produce a hardware
 * guarantee, and a system that pretended otherwise would let a passphrase plus a
 * TOTP code stand in for the Secure Enclave.
 */
fun strengthOf(factors: Set<AuthFactorKind>): AuthStrength =
    factors.maxOfOrNull { it.maxStrength } ?: AuthStrength.NONE
