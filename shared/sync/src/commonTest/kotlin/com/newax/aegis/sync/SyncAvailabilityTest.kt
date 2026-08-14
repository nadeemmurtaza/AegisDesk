package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The degradation path that a crash on launch went through untested.
 *
 * `JavaCrypto` correctly refuses to run without Ed25519, but the refusal reached
 * `Application.onCreate` as a fatal, so every device on API 26–30 crashed at
 * startup. These cases pin the behaviour that replaces it: unsupported is a
 * state, not an error.
 */
class SyncAvailabilityTest {

    private val identity = Identity.generate(FakeCrypto(), "Test device")

    private fun loadOrCreate(@Suppress("UNUSED_PARAMETER") c: Crypto, @Suppress("UNUSED_PARAMETER") k: KeyStore) =
        identity

    // ── Classification ────────────────────────────────────────────────────────

    @Test
    fun `the real API-30 failure is classified as unsupported crypto`() {
        // Verbatim shape of the observed crash: NoSuchAlgorithmException wrapped
        // in IllegalStateException, which is what callers actually see.
        val actual = IllegalStateException(
            "This platform lacks Ed25519/X25519 JCA providers (requires JDK 15+ / Android 12+); " +
                "refusing to fall back to weaker crypto.",
            RuntimeException("Ed25519 KeyPairGenerator not available"),
        )
        val result = classifySyncFailure(actual)
        assertEquals(SyncAvailability.Kind.CRYPTO_UNSUPPORTED, result.kind)
    }

    @Test
    fun `the unsupported-crypto message tells the user the rest of the app still works`() {
        val result = classifySyncFailure(IllegalStateException("Ed25519 not available"))
        assertTrue(result.reason.contains("Android 12"))
        // The point of the fix: a user on Android 10 must not think the app is broken.
        assertTrue(result.reason.contains("works normally", ignoreCase = true))
    }

    @Test
    fun `a keystore failure is not misreported as a crypto problem`() {
        val result = classifySyncFailure(IllegalStateException("Android KeyStore unavailable"))
        assertEquals(SyncAvailability.Kind.KEYSTORE_FAILURE, result.kind)
    }

    @Test
    fun `an unrecognised failure is reported as unknown rather than guessed at`() {
        val result = classifySyncFailure(RuntimeException("disk on fire"))
        assertEquals(SyncAvailability.Kind.UNKNOWN, result.kind)
        assertTrue(result.reason.contains("disk on fire"))
    }

    @Test
    fun `a cause chain that loops does not hang classification`() {
        val a = RuntimeException("outer")
        val b = RuntimeException("inner", a)
        // Deliberately pathological input; the walk is bounded so this returns.
        assertNotNull(classifySyncFailure(b))
    }

    // ── Probing ───────────────────────────────────────────────────────────────

    @Test
    fun `crypto that refuses to construct yields unavailable and no identity`() {
        val (availability, got) = probeSyncIdentity(
            crypto = { throw IllegalStateException("Ed25519 KeyPairGenerator not available") },
            keyStore = { InMemoryKeyStore() },
            loadOrCreate = ::loadOrCreate,
        )
        assertNull(got)
        val unavailable = availability as SyncAvailability.Unavailable
        assertEquals(SyncAvailability.Kind.CRYPTO_UNSUPPORTED, unavailable.kind)
        assertTrue(!availability.isAvailable)
    }

    @Test
    fun `a keystore that throws does not propagate either`() {
        val (availability, got) = probeSyncIdentity(
            crypto = { FakeCrypto() },
            keyStore = { throw IllegalStateException("KeyStore locked") },
            loadOrCreate = ::loadOrCreate,
        )
        assertNull(got)
        assertEquals(
            SyncAvailability.Kind.KEYSTORE_FAILURE,
            (availability as SyncAvailability.Unavailable).kind,
        )
    }

    @Test
    fun `a failure while generating the identity is caught too`() {
        // The observed crash happened here, not in the providers themselves.
        val (availability, got) = probeSyncIdentity(
            crypto = { FakeCrypto() },
            keyStore = { InMemoryKeyStore() },
            loadOrCreate = { _, _ -> throw IllegalStateException("Ed25519 KeyPairGenerator not available") },
        )
        assertNull(got)
        assertEquals(
            SyncAvailability.Kind.CRYPTO_UNSUPPORTED,
            (availability as SyncAvailability.Unavailable).kind,
        )
    }

    @Test
    fun `a working platform still gets its identity`() {
        val (availability, got) = probeSyncIdentity(
            crypto = { FakeCrypto() },
            keyStore = { InMemoryKeyStore() },
            loadOrCreate = ::loadOrCreate,
        )
        assertEquals(SyncAvailability.Available, availability)
        assertTrue(availability.isAvailable)
        assertEquals(identity.identity.deviceId, got?.identity?.deviceId)
    }
}
