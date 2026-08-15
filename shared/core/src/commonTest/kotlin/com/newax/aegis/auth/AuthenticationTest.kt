package com.newax.aegis.auth

import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.authority.PolicyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The properties that make this an authentication system rather than a boolean.
 *
 * Case names are claims, matching the idiom in `ActionGateTest` — a failure
 * report should read as a statement about safety, not as a method name.
 */
class AuthenticationTest {

    private val profile = ProfileRef("work")
    private val other = ProfileRef("personal")
    private val send = ProposedAction.Send("transfer 5000")
    private val otherAction = ProposedAction.Send("say hello")

    private class FakeCustody(
        var tier: CustodyTier = CustodyTier.HARDWARE,
        var factors: Set<AuthFactorKind> = setOf(AuthFactorKind.BIOMETRIC_STRONG),
        var result: UnlockResult? = null,
        var validForMs: Long = 5 * 60 * 1000L,
    ) : KeyCustody {
        var lockCalls = 0
        override fun tier() = tier
        override fun availableFactors() = factors
        override fun unlock(profile: ProfileRef, minimumStrength: AuthStrength): UnlockResult =
            result ?: UnlockResult.Unlocked(factors, tier, validForMs)
        override fun lock(profile: ProfileRef) { lockCalls++ }
    }

    private class TestClock(var nowMs: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = nowMs
    }

    private fun gate(
        custody: KeyCustody = FakeCustody(),
        clock: TestClock = TestClock(),
        floor: AuthStrength = AuthStrength.NONE,
    ) = AuthenticationGate(profile, custody, clock, strengthFloor = floor)

    // ── The ladder ────────────────────────────────────────────────────────────

    @Test
    fun `strong confirmation demands a hardware-bound key rather than merely a prompt`() {
        assertEquals(AuthStrength.HARDWARE_BOUND, AuthLadder.requiredStrength(PolicyMode.STRONG_CONFIRMATION))
    }

    @Test
    fun `a missing proof clears nothing that requires authentication`() {
        assertFalse(AuthLadder.clears(null, PolicyMode.APPROVAL, profile, send, nowMs = 1_000L))
        assertFalse(AuthLadder.clears(null, PolicyMode.STRONG_CONFIRMATION, profile, send, nowMs = 1_000L))
        // …but a mode that requires nothing still passes, so AUTO is not broken.
        assertTrue(AuthLadder.clears(null, PolicyMode.AUTO, profile, send, nowMs = 1_000L))
    }

    @Test
    fun `policy can raise a floor and has no way to lower one`() {
        assertEquals(
            AuthStrength.HARDWARE_BOUND,
            AuthLadder.raiseTo(AuthStrength.PRESENCE, AuthStrength.HARDWARE_BOUND),
        )
        // Passing a weaker floor cannot weaken the base — lowering is not expressible.
        assertEquals(
            AuthStrength.HARDWARE_BOUND,
            AuthLadder.raiseTo(AuthStrength.HARDWARE_BOUND, AuthStrength.NONE),
        )
    }

    @Test
    fun `stacking software factors never reaches a hardware guarantee`() {
        val stacked = setOf(AuthFactorKind.PASSPHRASE, AuthFactorKind.TOTP, AuthFactorKind.BIOMETRIC_WEAK)
        assertEquals(AuthStrength.VERIFIED, strengthOf(stacked))
        assertFalse(strengthOf(stacked).satisfies(AuthStrength.HARDWARE_BOUND))
    }

    @Test
    fun `voice can never satisfy anything above presence`() {
        assertEquals(AuthStrength.PRESENCE, AuthFactorKind.VOICE.maxStrength)
        assertFalse(strengthOf(setOf(AuthFactorKind.VOICE)).satisfies(AuthStrength.VERIFIED))
    }

    // ── Proof binding ─────────────────────────────────────────────────────────

    @Test
    fun `a proof issued for one action cannot approve a different one`() {
        val clock = TestClock()
        val g = gate(clock = clock)
        val outcome = g.challenge(send, PolicyMode.STRONG_CONFIRMATION)
        val proof = assertIs<AuthOutcome.Authorized>(outcome).proof
        assertNotNull(proof)

        assertTrue(proof.authorizes(send, profile, AuthStrength.HARDWARE_BOUND, clock.nowMs))
        assertFalse(proof.authorizes(otherAction, profile, AuthStrength.HARDWARE_BOUND, clock.nowMs))
    }

    @Test
    fun `a proof from one profile cannot approve an action in another`() {
        val clock = TestClock()
        val proof = assertIs<AuthOutcome.Authorized>(
            gate(clock = clock).challenge(send, PolicyMode.STRONG_CONFIRMATION),
        ).proof!!
        assertFalse(proof.authorizes(send, other, AuthStrength.HARDWARE_BOUND, clock.nowMs))
    }

    @Test
    fun `a proof expires`() {
        val clock = TestClock()
        val proof = assertIs<AuthOutcome.Authorized>(
            gate(clock = clock).challenge(send, PolicyMode.STRONG_CONFIRMATION),
        ).proof!!
        assertTrue(proof.isValidAt(clock.nowMs))
        assertFalse(proof.isValidAt(proof.expiresAtMs))
        assertFalse(proof.isValidAt(proof.expiresAtMs + 1))
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    @Test
    fun `an existing session never short-circuits strong confirmation`() {
        val clock = TestClock()
        val g = gate(clock = clock)
        // Establish a live session at the highest strength there is.
        g.challenge(send, PolicyMode.STRONG_CONFIRMATION)
        assertIs<LockState.Unlocked>(g.lockState)

        // A second spend still challenges. A recent unlock is not consent to spend.
        assertIs<AuthOutcome.ChallengeRequired>(g.authorize(otherAction, PolicyMode.STRONG_CONFIRMATION))
    }

    @Test
    fun `a session does cover repeated approval-level actions`() {
        val clock = TestClock()
        val g = gate(clock = clock)
        g.challenge(send, PolicyMode.APPROVAL)
        assertIs<AuthOutcome.Authorized>(g.authorize(otherAction, PolicyMode.APPROVAL))
    }

    @Test
    fun `an expired session stops authorizing`() {
        val clock = TestClock()
        val g = gate(clock = clock)
        g.challenge(send, PolicyMode.APPROVAL)
        clock.nowMs += 10 * 60 * 1000L
        assertIs<AuthOutcome.ChallengeRequired>(g.authorize(otherAction, PolicyMode.APPROVAL))
    }

    @Test
    fun `locking drops custody and the session`() {
        val custody = FakeCustody()
        val g = gate(custody = custody)
        g.challenge(send, PolicyMode.APPROVAL)
        g.lock()
        assertEquals(LockState.Locked, g.lockState)
        assertEquals(1, custody.lockCalls)
    }

    // ── Refusing to downgrade ─────────────────────────────────────────────────

    @Test
    fun `a platform that returns a weaker factor than asked for is refused`() {
        // Custody claims success, but only with a Class 2 biometric.
        val custody = FakeCustody(factors = setOf(AuthFactorKind.BIOMETRIC_WEAK))
        val g = gate(custody = custody)
        val outcome = g.challenge(send, PolicyMode.STRONG_CONFIRMATION)
        assertEquals(UnlockFailure.FACTOR_UNAVAILABLE, assertIs<AuthOutcome.Denied>(outcome).reason)
        assertEquals(LockState.Locked, g.lockState)
    }

    // ── Lockout ───────────────────────────────────────────────────────────────

    @Test
    fun `repeated failures throttle but cancelling does not count as a failure`() {
        val clock = TestClock()
        val custody = FakeCustody(result = UnlockResult.Failed(UnlockFailure.CANCELLED))
        val g = gate(custody = custody, clock = clock)

        repeat(10) { g.challenge(send, PolicyMode.APPROVAL) }
        assertEquals(0, g.lockoutState.consecutiveFailures)

        custody.result = UnlockResult.Failed(UnlockFailure.REJECTED)
        repeat(4) { g.challenge(send, PolicyMode.APPROVAL) }
        assertTrue(g.lockoutState.isLockedAt(clock.nowMs))
        assertEquals(UnlockFailure.THROTTLED, assertIs<AuthOutcome.Denied>(g.authorize(send, PolicyMode.APPROVAL)).reason)
    }

    @Test
    fun `backoff doubles but is capped so an attacker cannot lock the owner out forever`() {
        val policy = LockoutPolicy(freeAttempts = 3, baseDelayMs = 1_000L, maxDelayMs = 8_000L)
        assertEquals(0L, policy.delayAfter(3))
        assertEquals(1_000L, policy.delayAfter(4))
        assertEquals(2_000L, policy.delayAfter(5))
        assertEquals(4_000L, policy.delayAfter(6))
        assertEquals(8_000L, policy.delayAfter(7))
        assertEquals(8_000L, policy.delayAfter(50))
    }

    @Test
    fun `a success clears the failure history`() {
        val clock = TestClock()
        val custody = FakeCustody(result = UnlockResult.Failed(UnlockFailure.REJECTED))
        val g = gate(custody = custody, clock = clock)
        repeat(2) { g.challenge(send, PolicyMode.APPROVAL) }
        assertEquals(2, g.lockoutState.consecutiveFailures)

        custody.result = null
        g.challenge(send, PolicyMode.APPROVAL)
        assertEquals(0, g.lockoutState.consecutiveFailures)
    }

    // ── Enrollment ────────────────────────────────────────────────────────────

    @Test
    fun `a software-only device is refused a hardware-requiring profile with a reason`() {
        val decision = EnrollmentGuard.evaluate(
            deviceTier = CustodyTier.SOFTWARE,
            requiredTier = CustodyTier.HARDWARE,
            alreadyEnrolled = null,
        )
        val refusal = assertIs<EnrollmentDecision.Refused>(decision)
        assertEquals(EnrollmentRefusal.INSUFFICIENT_CUSTODY, refusal.reason)
        assertTrue(refusal.explanation.contains("software-only"))
    }

    @Test
    fun `a stronger device may hold a profile with a lower requirement`() {
        assertEquals(
            EnrollmentDecision.Allowed,
            EnrollmentGuard.evaluate(CustodyTier.HARDWARE_ISOLATED, CustodyTier.HARDWARE, null),
        )
    }

    @Test
    fun `a revoked device is told to pair again rather than silently re-added`() {
        val revoked = DeviceEnrollment(
            deviceId = "d1",
            profile = profile,
            displayName = "Old laptop",
            enrolledTier = CustodyTier.HARDWARE,
            enrolledAtMs = 0L,
            revokedAtMs = 10L,
        )
        val refusal = assertIs<EnrollmentDecision.Refused>(
            EnrollmentGuard.evaluate(CustodyTier.HARDWARE, CustodyTier.HARDWARE, revoked),
        )
        assertEquals(EnrollmentRefusal.REVOKED, refusal.reason)
    }
}
