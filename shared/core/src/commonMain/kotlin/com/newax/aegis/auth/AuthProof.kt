package com.newax.aegis.auth

import com.newax.aegis.assistant.ProposedAction

/** Identifies a profile without depending on the tenancy module (docs/TENANCY_DESIGN.md §9, T-1). */
data class ProfileRef(val value: String) {
    init { require(value.isNotBlank()) { "ProfileRef must not be blank" } }
}

/**
 * Evidence that authentication happened, replacing the `biometricAuthenticated:
 * Boolean = false` parameter that `AuthorityManager.approve` carried.
 *
 * The boolean was the exact anti-pattern ENGINEERING.md §B5 names as wrong: any
 * caller could pass `true`, and because it defaulted, a caller that forgot about
 * authentication entirely still compiled. A default value on a security
 * parameter means the insecure path is the one you get by not thinking.
 *
 * This type fixes three specific things a boolean cannot express:
 *
 *  1. **What was proved** — [strength] and [factors], so `STRONG_CONFIRMATION`
 *     can reject a passphrase that a boolean would have flattened into `true`.
 *  2. **For how long** — [expiresAtMs]. A boolean never goes stale.
 *  3. **For what** — [boundTo]. A proof issued for one action cannot approve a
 *     different one. Without this, an approval for "send £5" is replayable
 *     against "send £5000", which is the whole game for a compromised planner.
 *
 * ### What this type does not do
 *
 * It is not unforgeable. The constructor is private and issuance runs through
 * [AuthenticationGate], so forging one is a deliberate, reviewable act inside
 * `shared:core` rather than a one-character mistake anywhere in the tree — but
 * module-local code could still do it. **The real control is that the action
 * needs the key**, which lives in hardware custody and is not carried here. This
 * type makes the authority spine honest and auditable; [KeyCustody] makes it
 * enforced. Neither substitutes for the other.
 */
class AuthProof private constructor(
    val profile: ProfileRef,
    val strength: AuthStrength,
    val factors: Set<AuthFactorKind>,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val boundTo: ProposedAction?,
) {
    /** True when [nowMs] is within the validity window. */
    fun isValidAt(nowMs: Long): Boolean = nowMs in issuedAtMs until expiresAtMs

    /**
     * True when this proof authorizes [action] for [profile] at [nowMs] to at
     * least [required] strength.
     *
     * A proof with a null [boundTo] is session-scoped — it authorizes anything
     * up to its strength within its window, which is why the gate only issues
     * unbound proofs below [AuthStrength.HARDWARE_BOUND].
     */
    fun authorizes(
        action: ProposedAction,
        profile: ProfileRef,
        required: AuthStrength,
        nowMs: Long,
    ): Boolean =
        this.profile == profile &&
            isValidAt(nowMs) &&
            strength.satisfies(required) &&
            (boundTo == null || boundTo == action)

    override fun toString(): String =
        "AuthProof(profile=${profile.value}, strength=$strength, factors=$factors, " +
            "bound=${boundTo != null}, expiresAtMs=$expiresAtMs)"

    internal companion object {
        /**
         * Issued only by [AuthenticationGate], and only after a custody unlock
         * for anything reaching [AuthStrength.HARDWARE_BOUND].
         */
        internal fun issue(
            profile: ProfileRef,
            factors: Set<AuthFactorKind>,
            issuedAtMs: Long,
            validForMs: Long,
            boundTo: ProposedAction?,
        ): AuthProof {
            require(factors.isNotEmpty()) { "a proof with no factors proves nothing" }
            require(validForMs > 0) { "validForMs must be positive" }
            return AuthProof(
                profile = profile,
                strength = strengthOf(factors),
                factors = factors.toSet(),
                issuedAtMs = issuedAtMs,
                expiresAtMs = issuedAtMs + validForMs,
                boundTo = boundTo,
            )
        }
    }
}
