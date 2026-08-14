package com.newax.aegis.auth

import com.newax.aegis.authority.PolicyMode

/**
 * Binds the policy ladder to real authentication strength (ENGINEERING.md §B5:
 * "bind auth strength to action risk … wire it to real key requirements rather
 * than UI-level checks").
 *
 * [PolicyMode] already says *how much ceremony* an action needs. This says what
 * that ceremony has to actually establish, so `STRONG_CONFIRMATION` means a
 * hardware-held key was unlocked rather than a dialog was shown.
 */
object AuthLadder {

    /** The minimum strength [mode] demands. */
    fun requiredStrength(mode: PolicyMode): AuthStrength = when (mode) {
        PolicyMode.AUTO -> AuthStrength.NONE
        PolicyMode.CONFIGURABLE -> AuthStrength.NONE
        PolicyMode.APPROVAL -> AuthStrength.PRESENCE
        PolicyMode.STRONG_CONFIRMATION -> AuthStrength.HARDWARE_BOUND
    }

    /**
     * Raises [base] to [floor], never lowers it.
     *
     * Organization bundles and user preference both feed through here. The
     * asymmetry is deliberate and matches TENANCY_DESIGN.md §4.2: a policy that
     * could lower a floor is not a floor. Written as `maxOf` so *lowering is not
     * expressible* — there is no argument to this function that weakens the
     * result, which is a stronger property than a check that refuses to.
     */
    fun raiseTo(base: AuthStrength, floor: AuthStrength): AuthStrength =
        if (floor.ordinal > base.ordinal) floor else base

    /**
     * Whether [proof] clears [mode] for this profile at [nowMs].
     *
     * A null proof clears only modes requiring [AuthStrength.NONE] — so
     * forgetting to pass one fails closed, which is the behaviour the old
     * defaulted boolean got backwards.
     */
    fun clears(
        proof: AuthProof?,
        mode: PolicyMode,
        profile: ProfileRef,
        action: com.newax.aegis.assistant.ProposedAction,
        nowMs: Long,
        floor: AuthStrength = AuthStrength.NONE,
    ): Boolean {
        val required = raiseTo(requiredStrength(mode), floor)
        if (required == AuthStrength.NONE) return true
        return proof != null && proof.authorizes(action, profile, required, nowMs)
    }
}
