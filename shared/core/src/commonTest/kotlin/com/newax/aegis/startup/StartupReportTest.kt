package com.newax.aegis.startup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The containment that two shipped crash-on-launch bugs went without.
 */
class StartupReportTest {

    @Test
    fun `an optional step that fails does not stop the ones after it`() {
        val report = StartupReport()
        var reachedLaterStep = false

        report.step("LearningEngine") { throw IllegalStateException("boom") }
        report.step("EmbeddingEngine") { reachedLaterStep = true }

        assertTrue(reachedLaterStep, "a later step must still run")
        assertEquals(listOf("LearningEngine"), report.failures.map { it.step })
    }

    @Test
    fun `an essential step that fails still stops everything`() {
        val report = StartupReport()
        assertFailsWith<IllegalStateException> {
            report.step("Database", StepCriticality.ESSENTIAL) {
                throw IllegalStateException("no database")
            }
        }
        // Rethrown, not recorded — the app is not starting, so there is nobody to tell.
        assertFalse(report.hasFailures)
    }

    /** Stands in for UnsatisfiedLinkError, which is JVM-only and so unusable here. */
    private class LinkageFailure(message: String) : Error(message)

    @Test
    fun `an Error is contained and not only an Exception`() {
        // The SQLCipher fault was UnsatisfiedLinkError — an Error, not an
        // Exception. Catching only Exception would have missed one of the two
        // real bugs this exists to contain.
        val report = StartupReport()
        report.step("Database driver") { throw LinkageFailure("No implementation found") }

        assertEquals(1, report.failures.size)
        assertEquals("LinkageFailure", report.failures.single().errorType)
    }

    @Test
    fun `a failure names the step so a vague bug report becomes an actionable one`() {
        val report = StartupReport()
        report.step("AgentRegistry") { throw IllegalStateException("db not ready") }

        val summary = report.failures.single().summary
        assertTrue(summary.contains("AgentRegistry"))
        assertTrue(summary.contains("db not ready"))
    }

    @Test
    fun `no throwable is retained because startup steps handle key material`() {
        val report = StartupReport()
        report.step("SecureKeyVault") { throw IllegalStateException("wrapped key unavailable") }

        val failure = report.failures.single()
        // Only type + message survive. A retained throwable can carry a
        // passphrase into a log through its cause chain or suppressed list.
        assertEquals("IllegalStateException", failure.errorType)
        assertEquals("wrapped key unavailable", failure.message)
        // The summary is what reaches a log, so it must carry nothing more than
        // those two fields plus the step name.
        assertEquals(
            "SecureKeyVault failed: IllegalStateException — wrapped key unavailable",
            failure.summary,
        )
    }

    @Test
    fun `degraded lists only the optional failures`() {
        val report = StartupReport()
        report.step("A") { throw RuntimeException("a") }
        report.step("B") { /* succeeds */ }
        report.step("C") { throw RuntimeException("c") }

        assertEquals(listOf("A", "C"), report.degraded.map { it.step })
        assertEquals(2, report.failures.size)
    }

    @Test
    fun `a clean start reports nothing`() {
        val report = StartupReport()
        report.step("A") { }
        report.step("B", StepCriticality.ESSENTIAL) { }
        assertFalse(report.hasFailures)
        assertTrue(report.degraded.isEmpty())
    }

    @Test
    fun `the failure list is a copy so a caller cannot mutate the record`() {
        val report = StartupReport()
        report.step("A") { throw RuntimeException("a") }
        val snapshot = report.failures
        report.step("B") { throw RuntimeException("b") }
        assertEquals(1, snapshot.size)
        assertEquals(2, report.failures.size)
    }
}
