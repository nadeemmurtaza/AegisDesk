package com.newax.aegis.sync

/**
 * Whether this device can hold a sync identity at all.
 *
 * ### Why this type exists
 *
 * [JavaCrypto] refuses to construct without Ed25519/X25519 JCA providers —
 * **Android 12 / API 31**, or JDK 15+ — rather than falling back to weaker
 * curves. That refusal is correct and stays.
 *
 * What was wrong was the *consequence*: identity generation ran eagerly from
 * `Application.onCreate`, so the throw became a crash on launch for every
 * device between `minSdk = 26` and API 30 — Android 8.0 through 11. A device
 * that merely cannot **sync** could not **run**.
 *
 * The distinction this type draws is the whole fix: *unsupported* is a state to
 * report, not an error to propagate. Sync is one feature; the assistant is the
 * product.
 */
sealed interface SyncAvailability {

    data object Available : SyncAvailability

    /**
     * Sync cannot start here. [reason] is shown to the user verbatim, so it is
     * written for them rather than for a log — a person reading it should learn
     * what is wrong and whether they can do anything about it.
     */
    data class Unavailable(val kind: Kind, val reason: String) : SyncAvailability

    enum class Kind {
        /**
         * The platform lacks Ed25519/X25519. Permanent for this OS version —
         * not worth retrying, and the user cannot fix it except by upgrading.
         */
        CRYPTO_UNSUPPORTED,

        /** The OS keystore refused. May be transient (locked device, storage). */
        KEYSTORE_FAILURE,

        /** Something else. Reported honestly rather than guessed at. */
        UNKNOWN,
    }

    val isAvailable: Boolean get() = this is Available
}

/**
 * Classify a failure raised while establishing identity.
 *
 * Matches on the message rather than the exception type because the JCA path
 * surfaces `NoSuchAlgorithmException` wrapped in `IllegalStateException`, and
 * the wrapper is what callers see. Falls back to [SyncAvailability.Kind.UNKNOWN]
 * rather than assuming — a mystery reported as a mystery is more useful than a
 * mystery reported as a crypto problem.
 */
fun classifySyncFailure(error: Throwable): SyncAvailability.Unavailable {
    val text = generateSequence(error) { it.cause }
        .take(8) // cause chains can loop; bound the walk
        .joinToString(" ") { "${it::class.simpleName} ${it.message.orEmpty()}" }

    return when {
        text.contains("Ed25519", ignoreCase = true) ||
            text.contains("X25519", ignoreCase = true) ||
            text.contains("NoSuchAlgorithm", ignoreCase = true) ->
            SyncAvailability.Unavailable(
                SyncAvailability.Kind.CRYPTO_UNSUPPORTED,
                "Device sync needs Android 12 or newer. Everything else works normally " +
                    "on this device.",
            )

        text.contains("KeyStore", ignoreCase = true) ||
            text.contains("Keychain", ignoreCase = true) ->
            SyncAvailability.Unavailable(
                SyncAvailability.Kind.KEYSTORE_FAILURE,
                "This device's secure key storage is unavailable, so sync cannot start.",
            )

        else ->
            SyncAvailability.Unavailable(
                SyncAvailability.Kind.UNKNOWN,
                "Sync could not start on this device: " +
                    (error.message ?: error::class.simpleName ?: "unknown error"),
            )
    }
}

/**
 * Establish identity without throwing, returning it alongside the availability.
 *
 * Takes the providers as functions so the failure paths are testable — on a JVM
 * with Ed25519 present, the interesting cases are unreachable otherwise, and an
 * untested degradation path is how a crash-on-launch shipped in the first place.
 *
 * [loadOrCreate] receives both providers and returns the identity; any throw
 * from any of the three is classified rather than propagated.
 */
fun probeSyncIdentity(
    crypto: () -> Crypto,
    keyStore: () -> KeyStore,
    loadOrCreate: (Crypto, KeyStore) -> StoredIdentity,
): Pair<SyncAvailability, StoredIdentity?> =
    try {
        val c = crypto()
        val ks = keyStore()
        SyncAvailability.Available to loadOrCreate(c, ks)
    } catch (e: Throwable) {
        classifySyncFailure(e) to null
    }
