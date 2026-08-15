package com.newax.aegis.startup

/**
 * Whether a startup step failing should stop the app.
 *
 * `NewaxApplication.onCreate` runs **22 eager initializers**, and until recently
 * every one of them was unguarded — so any single throw was a crash on launch.
 * Two shipped that way and were only found once CI could run the app at all:
 *
 *  - an eager Ed25519 call that killed every device below Android 12,
 *  - an unloaded SQLCipher native library that killed *every* device.
 *
 * Both were in steps the app does not actually need in order to start.
 */
enum class StepCriticality {
    /**
     * Nothing works without it — the database, key custody, the policy engine.
     * A failure here is fatal, and **should** be: an assistant running without
     * its authority spine is more dangerous than one that will not start.
     */
    ESSENTIAL,

    /**
     * One feature depends on it. A failure degrades that feature and is
     * recorded and surfaced, but the app starts.
     */
    OPTIONAL,
}

/** One startup step that failed. */
data class StartupFailure(
    val step: String,
    val criticality: StepCriticality,
    val errorType: String,
    val message: String?,
) {
    /** One line for a log or the dev console. Never includes secrets — see [StartupReport]. */
    val summary: String get() = "$step failed: $errorType${message?.let { " — $it" } ?: ""}"
}

/**
 * Collects startup failures so a degraded start is **visible** rather than silent.
 *
 * The point of the collection is that catching an exception and moving on is
 * itself an anti-pattern when nobody ever learns it happened. An app that
 * silently loses its learning engine looks like an app whose learning engine
 * does not work — a bug report nobody can act on. Recording the step by name
 * turns "the app is being weird" into "LearningEngine failed at startup".
 *
 * Deliberately holds **no throwable and no stack trace** — only the exception's
 * type name and message. Startup steps touch key vaults and database
 * passphrases, and a retained throwable can carry that material into a log or a
 * crash report. Type and message are enough to route a defect.
 */
class StartupReport {

    private val _failures = mutableListOf<StartupFailure>()

    /** Failures so far, oldest first. */
    val failures: List<StartupFailure> get() = _failures.toList()

    val hasFailures: Boolean get() = _failures.isNotEmpty()

    /** Optional steps that failed — the ones that produce degraded features. */
    val degraded: List<StartupFailure>
        get() = _failures.filter { it.criticality == StepCriticality.OPTIONAL }

    fun record(step: String, criticality: StepCriticality, error: Throwable) {
        _failures += StartupFailure(
            step = step,
            criticality = criticality,
            errorType = error::class.simpleName ?: "Throwable",
            message = error.message,
        )
    }

    fun clear() = _failures.clear()

    /**
     * Run [block] as a startup step.
     *
     * [StepCriticality.ESSENTIAL] rethrows — failing loudly is correct when the
     * alternative is an assistant with no policy engine. [StepCriticality.OPTIONAL]
     * records and continues.
     *
     * Catches [Throwable] rather than [Exception] on purpose: the two real
     * failures were an `IllegalStateException` and an `UnsatisfiedLinkError`, and
     * the second is an `Error`. Catching only `Exception` would have caught one
     * of the two bugs this exists to contain.
     *
     * `CancellationException` is not special-cased because startup steps here are
     * synchronous; if that changes, it must be rethrown rather than recorded.
     */
    inline fun step(
        name: String,
        criticality: StepCriticality = StepCriticality.OPTIONAL,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            if (criticality == StepCriticality.ESSENTIAL) throw t
            record(name, criticality, t)
        }
    }
}
