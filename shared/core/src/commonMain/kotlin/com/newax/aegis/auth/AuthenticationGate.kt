package com.newax.aegis.auth

import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.authority.PolicyMode

/** Whether a profile is currently open on this device, and until when. */
sealed interface LockState {
    data object Locked : LockState

    data class Unlocked(
        val profile: ProfileRef,
        val strength: AuthStrength,
        val factors: Set<AuthFactorKind>,
        val unlockedAtMs: Long,
        val expiresAtMs: Long,
    ) : LockState {
        fun isValidAt(nowMs: Long): Boolean = nowMs in unlockedAtMs until expiresAtMs
    }
}

/** Outcome of asking the gate to authorize something. */
sealed interface AuthOutcome {
    /** Cleared. [proof] is null only when the action required [AuthStrength.NONE]. */
    data class Authorized(val proof: AuthProof?) : AuthOutcome

    /** A factor must be presented; the caller shows the prompt and calls back. */
    data class ChallengeRequired(val minimumStrength: AuthStrength) : AuthOutcome

    /** Refused. [retryAfterMs] is non-zero only for [UnlockFailure.THROTTLED]. */
    data class Denied(val reason: UnlockFailure, val retryAfterMs: Long = 0L) : AuthOutcome
}

/**
 * The one place authentication decisions are made (docs/AUTH_DESIGN.md §5).
 *
 * Everything else — the approval card, the settings toggle, the automation
 * switch — asks this. Two call sites doing their own `BiometricPrompt` is how
 * the repo ended up with two different answers to "is this user authenticated",
 * neither bound to a key.
 *
 * ### Deliberately not a singleton
 *
 * One gate per profile, constructed with that profile's custody. The existing
 * process-wide `object` holders are exactly what tenancy T-1 has to retire, and
 * a *shared* authentication gate would be worse than most: it is the thing whose
 * whole job is keeping two profiles apart.
 *
 * ### Deliberately not suspending
 *
 * [KeyCustody.unlock] is where the platform prompt happens, and platform prompts
 * are callback-shaped on every body. Keeping this synchronous and pure-ish means
 * the decision logic is unit-testable with a fake custody; the async edges live
 * in the adapters.
 */
class AuthenticationGate(
    private val profile: ProfileRef,
    private val custody: KeyCustody,
    private val clock: () -> Long,
    private val lockoutPolicy: LockoutPolicy = LockoutPolicy(),
    /** Raised by org policy; never lowered. See [AuthLadder.raiseTo]. */
    private val strengthFloor: AuthStrength = AuthStrength.NONE,
    /** How long an unbound session proof stays valid. */
    private val sessionValidityMs: Long = 5 * 60 * 1000L,
    /** How long a proof bound to one action stays valid. Short by design. */
    private val boundProofValidityMs: Long = 60 * 1000L,
) {
    var lockState: LockState = LockState.Locked
        private set

    var lockoutState: LockoutState = LockoutState()
        private set

    /**
     * Decide what [action] under [mode] needs right now.
     *
     * Returns [AuthOutcome.Authorized] without a challenge when an existing
     * session already covers it — but **never** for [PolicyMode.STRONG_CONFIRMATION].
     * A five-minute-old unlock is not consent to spend money; the strong rung
     * always re-prompts and always produces a proof bound to that one action.
     */
    fun authorize(action: ProposedAction, mode: PolicyMode): AuthOutcome {
        val now = clock()
        val required = AuthLadder.raiseTo(AuthLadder.requiredStrength(mode), strengthFloor)

        if (required == AuthStrength.NONE) return AuthOutcome.Authorized(null)

        if (lockoutState.isLockedAt(now)) {
            return AuthOutcome.Denied(UnlockFailure.THROTTLED, lockoutState.remainingMs(now))
        }

        if (required == AuthStrength.HARDWARE_BOUND) {
            return AuthOutcome.ChallengeRequired(required)
        }

        val session = lockState as? LockState.Unlocked
        if (session != null &&
            session.profile == profile &&
            session.isValidAt(now) &&
            session.strength.satisfies(required)
        ) {
            return AuthOutcome.Authorized(
                AuthProof.issue(
                    profile = profile,
                    factors = session.factors,
                    issuedAtMs = now,
                    validForMs = session.expiresAtMs - now,
                    boundTo = null,
                ),
            )
        }

        return AuthOutcome.ChallengeRequired(required)
    }

    /**
     * Run the challenge for [action] and, on success, issue a proof bound to it.
     *
     * The binding is the reason this takes the action rather than returning a
     * general-purpose token: a proof that is not bound to what it approves can
     * be replayed against a different action, and the planner that chose the
     * action is the component we are least willing to trust.
     */
    fun challenge(action: ProposedAction, mode: PolicyMode): AuthOutcome {
        val now = clock()
        val required = AuthLadder.raiseTo(AuthLadder.requiredStrength(mode), strengthFloor)

        if (required == AuthStrength.NONE) return AuthOutcome.Authorized(null)

        if (lockoutState.isLockedAt(now)) {
            return AuthOutcome.Denied(UnlockFailure.THROTTLED, lockoutState.remainingMs(now))
        }

        return when (val result = custody.unlock(profile, required)) {
            is UnlockResult.Failed -> {
                // Dismissing a prompt is not a wrong guess.
                if (result.reason != UnlockFailure.CANCELLED) {
                    lockoutState = lockoutState.recordFailure(lockoutPolicy, now)
                }
                if (result.reason == UnlockFailure.KEY_INVALIDATED) lockState = LockState.Locked
                AuthOutcome.Denied(result.reason, lockoutState.remainingMs(now))
            }

            is UnlockResult.Unlocked -> {
                // Trust what the platform says it did, not what we asked for.
                val achieved = strengthOf(result.factors)
                if (!achieved.satisfies(required)) {
                    lockoutState = lockoutState.recordFailure(lockoutPolicy, now)
                    return AuthOutcome.Denied(UnlockFailure.FACTOR_UNAVAILABLE)
                }

                lockoutState = lockoutState.recordSuccess()
                val sessionMs = minOf(result.validForMs, sessionValidityMs)
                lockState = LockState.Unlocked(
                    profile = profile,
                    strength = achieved,
                    factors = result.factors,
                    unlockedAtMs = now,
                    expiresAtMs = now + sessionMs,
                )
                AuthOutcome.Authorized(
                    AuthProof.issue(
                        profile = profile,
                        factors = result.factors,
                        issuedAtMs = now,
                        validForMs = minOf(result.validForMs, boundProofValidityMs),
                        boundTo = action,
                    ),
                )
            }
        }
    }

    /**
     * Close the profile. Idempotent.
     *
     * Called on idle timeout, on explicit lock, and — the one that matters for
     * tenancy — **on every profile switch**. Leaving Work unlocked while
     * Personal is on screen is the cross-contamination the whole profile model
     * exists to prevent.
     */
    fun lock() {
        custody.lock(profile)
        lockState = LockState.Locked
    }

    /** Drops an expired session so a stale [LockState.Unlocked] cannot linger. */
    fun expireIfStale() {
        val session = lockState as? LockState.Unlocked ?: return
        if (!session.isValidAt(clock())) lock()
    }
}
