package com.newax.aegis.engine.procedure

import com.newax.aegis.engine.procedure.ExecutionGuard.BlockReason
import com.newax.aegis.engine.procedure.ExecutionGuard.GuardContext
import com.newax.aegis.engine.procedure.ExecutionGuard.GuardResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-flight guard that was written and never called.
 *
 * These are the first tests it has ever had — it took a `Context` neither
 * function used, which meant testing it appeared to need a device. It does not.
 */
class ExecutionGuardTest {

    private val banking = "com.easypaisa"
    private val target = "com.whatsapp"

    // ── Never automate inside a protected app ─────────────────────────────────

    @Test
    fun `a password manager is never automated`() {
        assertEquals(GuardResult.BLOCKED, ExecutionGuard.check("com.lastpass.lpandroid"))
        assertEquals(GuardResult.BLOCKED, ExecutionGuard.check("com.agilebits.onepassword"))
    }

    @Test
    fun `banking and authenticator apps are never automated`() {
        assertTrue(ExecutionGuard.isProtected(banking))
        assertTrue(ExecutionGuard.isProtected("com.google.android.apps.authenticator2"))
        assertTrue(ExecutionGuard.isProtected("com.android.settings"))
    }

    @Test
    fun `an ordinary app is allowed`() {
        assertEquals(GuardResult.ALLOWED, ExecutionGuard.check(target))
        assertFalse(ExecutionGuard.isProtected(target))
    }

    @Test
    fun `a null package is allowed because it means between screens`() {
        // The accessibility service reports null during transitions. Blocking
        // every transition would stop all automation, so the per-step context
        // check below is what catches a move to the wrong app.
        assertEquals(GuardResult.ALLOWED, ExecutionGuard.check(null))
        assertEquals(GuardResult.ALLOWED, ExecutionGuard.check(""))
    }

    // ── Pre-flight: is this still the screen the plan assumed? ────────────────

    @Test
    fun `staying in the expected app is allowed`() {
        val (result, reason) = ExecutionGuard.checkWithContext(
            currentPackage = target,
            guardContext = GuardContext(expectedPackage = target),
        )
        assertEquals(GuardResult.ALLOWED, result)
        assertEquals(null, reason)
    }

    @Test
    fun `an app switching under the procedure aborts`() {
        val (result, reason) = ExecutionGuard.checkWithContext(
            currentPackage = "com.instagram.android",
            guardContext = GuardContext(expectedPackage = target),
        )
        assertEquals(GuardResult.BLOCKED, result)
        // Not WRONG_PERSON, which is what this used to report. An app switching
        // under you is not a person mix-up, and an audit row saying so misleads.
        assertEquals(BlockReason.UNEXPECTED_PACKAGE, reason)
    }

    @Test
    fun `a null current package does not count as the wrong screen`() {
        val (result, _) = ExecutionGuard.checkWithContext(
            currentPackage = null,
            guardContext = GuardContext(expectedPackage = target),
        )
        assertEquals(GuardResult.ALLOWED, result)
    }

    @Test
    fun `no expected package means the screen check is skipped`() {
        val (result, _) = ExecutionGuard.checkWithContext(
            currentPackage = "com.anything",
            guardContext = GuardContext(),
        )
        assertEquals(GuardResult.ALLOWED, result)
    }

    // ── Ordering ──────────────────────────────────────────────────────────────

    @Test
    fun `a protected app is blocked even when the procedure expects it`() {
        // A procedure naming a banking app as its target must not thereby be
        // allowed to drive it — the protected check runs first, unconditionally.
        val (result, reason) = ExecutionGuard.checkWithContext(
            currentPackage = banking,
            guardContext = GuardContext(expectedPackage = banking),
        )
        assertEquals(GuardResult.BLOCKED, result)
        assertEquals(BlockReason.PROTECTED_PACKAGE, reason)
    }

    @Test
    fun `a financial action is refused even in the right app`() {
        val (result, reason) = ExecutionGuard.checkWithContext(
            currentPackage = target,
            guardContext = GuardContext(expectedPackage = target, isFinancialAction = true),
        )
        assertEquals(GuardResult.BLOCKED, result)
        assertEquals(BlockReason.FINANCIAL_ACTION, reason)
    }

    @Test
    fun `protection outranks a financial action so the reason names the stronger rule`() {
        val (result, reason) = ExecutionGuard.checkWithContext(
            currentPackage = banking,
            guardContext = GuardContext(isFinancialAction = true),
        )
        assertEquals(GuardResult.BLOCKED, result)
        assertEquals(BlockReason.PROTECTED_PACKAGE, reason)
    }
}
