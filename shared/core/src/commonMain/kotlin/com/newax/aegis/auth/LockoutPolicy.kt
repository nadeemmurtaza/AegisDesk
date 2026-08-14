package com.newax.aegis.auth

/**
 * Throttles repeated failed unlock attempts.
 *
 * ### What this is, and what it is not
 *
 * Lockout is **not** the defence against a determined attacker. Profile data is
 * encrypted at rest with a key derived through a memory-hard KDF; an attacker
 * with the storage does not go through this class at all — they attack the KDF
 * offline, where the Argon2id parameters are the entire defence (ENGINEERING.md
 * §B5, "Data at rest").
 *
 * What lockout defends is the **online** path: someone holding the unlocked
 * device, guessing at a passphrase prompt. There, delay is genuinely effective,
 * because a human guessing gets a handful of attempts per minute instead of
 * thousands.
 *
 * Stating the boundary matters because a lockout counter feels like security and
 * is easy to over-trust. If it is stored somewhere a reinstall clears, it is
 * bypassable — and it was never the control that mattered anyway.
 *
 * Deliberately excludes [UnlockFailure.CANCELLED]: dismissing a prompt is not a
 * failed guess, and counting it means a user who taps away twice gets punished
 * for changing their mind.
 */
data class LockoutPolicy(
    val freeAttempts: Int = 3,
    val baseDelayMs: Long = 30_000L,
    val maxDelayMs: Long = 15 * 60 * 1000L,
) {
    init {
        require(freeAttempts >= 0) { "freeAttempts must not be negative" }
        require(baseDelayMs > 0) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be at least baseDelayMs" }
    }

    /**
     * How long to lock out after [consecutiveFailures], doubling each time past
     * the free attempts and capped at [maxDelayMs].
     *
     * Capped rather than escalating to permanent: an uncapped backoff is a
     * denial-of-service an attacker can inflict on the owner by failing
     * deliberately, and the owner's own data is the thing being denied.
     */
    fun delayAfter(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= freeAttempts) return 0L
        val steps = consecutiveFailures - freeAttempts - 1
        var delay = baseDelayMs
        repeat(steps) {
            if (delay >= maxDelayMs) return maxDelayMs
            delay *= 2
        }
        return if (delay > maxDelayMs) maxDelayMs else delay
    }
}

/** Failed-attempt bookkeeping for one profile. Pure data — the caller persists it. */
data class LockoutState(
    val consecutiveFailures: Int = 0,
    val lockedUntilMs: Long = 0L,
) {
    fun isLockedAt(nowMs: Long): Boolean = nowMs < lockedUntilMs

    /** Remaining lockout in millis, or 0 when not locked. */
    fun remainingMs(nowMs: Long): Long = if (isLockedAt(nowMs)) lockedUntilMs - nowMs else 0L

    fun recordFailure(policy: LockoutPolicy, nowMs: Long): LockoutState {
        val failures = consecutiveFailures + 1
        return LockoutState(failures, nowMs + policy.delayAfter(failures))
    }

    /** A successful unlock clears the history entirely. */
    fun recordSuccess(): LockoutState = LockoutState()
}
