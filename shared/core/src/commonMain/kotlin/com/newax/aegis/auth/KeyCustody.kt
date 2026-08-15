package com.newax.aegis.auth

/**
 * Where a profile's wrapping key lives, and what it takes to use it.
 *
 * The tier is surfaced to the user rather than silently degraded (ENGINEERING.md
 * §B5), and it gates enrollment: a profile requiring [HARDWARE] is refused on a
 * [SOFTWARE] device **with the reason shown**, because a profile is only as
 * protected as the weakest device holding it (TENANCY_DESIGN.md §6).
 */
enum class CustodyTier {
    /**
     * A dedicated security chip: StrongBox, Secure Enclave, or a discrete TPM.
     * Key material never enters application memory and survives OS compromise.
     */
    HARDWARE_ISOLATED,

    /**
     * TEE-backed: Android Keystore without StrongBox, TPM via CNG. Key material
     * is outside the app process but shares the main SoC.
     */
    HARDWARE,

    /**
     * OS-scoped software protection — DPAPI, an encrypted file with an
     * OS-derived key. Bound to a user account, **not** to hardware. This is what
     * Windows has today, and the tier the user must be told about.
     */
    SOFTWARE,

    /** No custody available. Enrollment must be refused, not downgraded. */
    NONE,
}

/** Why an unlock attempt did not produce usable key material. */
enum class UnlockFailure {
    /** The factor was presented and rejected — wrong passphrase, no biometric match. */
    REJECTED,

    /** The user dismissed the prompt. Not a failed attempt; must not count toward lockout. */
    CANCELLED,

    /** Locked out by [LockoutPolicy] or by the platform's own throttle. */
    THROTTLED,

    /**
     * The key is gone: biometric enrollment changed and invalidated it
     * (`setInvalidatedByBiometricEnrollment`), or the keystore was cleared.
     * Recovery, not retry.
     */
    KEY_INVALIDATED,

    /** The requested factor is not available on this device or not enrolled. */
    FACTOR_UNAVAILABLE,

    /** The device cannot meet the profile's required custody tier. */
    TIER_TOO_LOW,
}

sealed interface UnlockResult {
    /**
     * Key material is available for the requested window.
     *
     * [factors] is what the platform actually satisfied — which may be less than
     * what was asked for, and the gate checks rather than assumes. A platform
     * that falls back from Class 3 to Class 2 without saying so is how a
     * `STRONG_CONFIRMATION` quietly becomes a `VERIFIED`.
     */
    data class Unlocked(
        val factors: Set<AuthFactorKind>,
        val tier: CustodyTier,
        val validForMs: Long,
    ) : UnlockResult

    data class Failed(val reason: UnlockFailure, val detail: String? = null) : UnlockResult
}

/**
 * The per-platform custody seam: Android Keystore, iOS Secure Enclave, Windows
 * CNG/TPM, macOS Keychain.
 *
 * Modelled as an interface rather than `expect`/`actual` to match the existing
 * idiom in this repo (`sync.KeyStore`, `platform.secrets.SecretsCapability`) and
 * so tests can substitute a fake without an actual for all five targets.
 *
 * **Implementations own the hard part.** This interface is deliberately thin
 * because the security lives in the platform calls behind it — on Android,
 * `setIsStrongBoxBacked`, `setUserAuthenticationRequired`,
 * `setUnlockedDeviceRequired`, `setInvalidatedByBiometricEnrollment`, and a
 * `CryptoObject`-bound prompt. An implementation that returns [UnlockResult.Unlocked]
 * without those satisfies this type and defeats the design.
 */
interface KeyCustody {

    /** The best tier this device can provide. Cheap; safe to call for UI. */
    fun tier(): CustodyTier

    /** Which factors are available and enrolled right now. */
    fun availableFactors(): Set<AuthFactorKind>

    /**
     * Attempt to unlock the wrapping key for [profile], requiring at least
     * [minimumStrength].
     *
     * Implementations must **fail rather than downgrade**: asked for
     * [AuthStrength.HARDWARE_BOUND] on a device with only Class 2 biometrics,
     * return [UnlockFailure.FACTOR_UNAVAILABLE], never a weaker success.
     */
    fun unlock(profile: ProfileRef, minimumStrength: AuthStrength): UnlockResult

    /** Drop cached key material for [profile]. Must be safe to call when already locked. */
    fun lock(profile: ProfileRef)
}
