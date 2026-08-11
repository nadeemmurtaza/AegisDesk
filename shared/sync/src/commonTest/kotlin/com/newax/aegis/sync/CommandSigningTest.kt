package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CommandSigning round-trip — the per-entry Ed25519 signature the target
 * verifies before TTL/allowlist/policy (docs/SYNC_DESIGN.md §6, Fix A).
 * Uses the deterministic [FakeCrypto] (real HMAC semantics), so the tests are
 * identical on every target.
 */
class CommandSigningTest {

    private val crypto = FakeCrypto(seed = 7)
    private val sender = crypto.newSignKeyPair()

    /** A fixed far-future ttl — deterministic on every platform (no time source). */
    private val ttl = 4_100_000_000_000L

    private fun signAndVerify(args: Map<String, String>): Boolean {
        val sig = CommandSigning.sign(crypto, sender.privateKey, "open_app", ttl, args)
        return CommandSigning.verify(
            crypto, sender.publicKey, "open_app", ttl, args, Hex.encode(sig)
        )
    }

    @Test
    fun roundTripVerifies() {
        assertTrue(signAndVerify(mapOf("name" to "Spotify")))
        assertTrue(signAndVerify(emptyMap()))
        assertTrue(signAndVerify(mapOf("a" to "1", "b" to "2", "c" to "3")))
    }

    @Test
    fun tamperedArgsFail() {
        val sig = CommandSigning.sign(crypto, sender.privateKey, "open_app", ttl, mapOf("name" to "Spotify"))
        // Same class/ttl, different arg value → different canonical bytes.
        assertFalse(
            CommandSigning.verify(crypto, sender.publicKey, "open_app", ttl, mapOf("name" to "Music"), Hex.encode(sig))
        )
        // Extra arg → different canonical bytes.
        assertFalse(
            CommandSigning.verify(
                crypto, sender.publicKey, "open_app", ttl, mapOf("name" to "Spotify", "extra" to "x"), Hex.encode(sig)
            )
        )
    }

    @Test
    fun wrongClassOrTtlFails() {
        val args = mapOf("name" to "Spotify")
        val sig = CommandSigning.sign(crypto, sender.privateKey, "open_app", ttl, args)
        assertFalse(
            CommandSigning.verify(crypto, sender.publicKey, "run_shell", ttl, args, Hex.encode(sig))
        )
        assertFalse(
            CommandSigning.verify(crypto, sender.publicKey, "open_app", ttl + 1L, args, Hex.encode(sig))
        )
    }

    @Test
    fun wrongKeyOrMissingSignatureFails() {
        val args = mapOf("name" to "Spotify")
        val sig = CommandSigning.sign(crypto, sender.privateKey, "open_app", ttl, args)
        val other = crypto.newSignKeyPair()
        // A different sender's public key must not verify.
        assertFalse(
            CommandSigning.verify(crypto, other.publicKey, "open_app", ttl, args, Hex.encode(sig))
        )
        // Missing / blank / malformed signature.
        assertFalse(CommandSigning.verify(crypto, sender.publicKey, "open_app", ttl, args, null))
        assertFalse(CommandSigning.verify(crypto, sender.publicKey, "open_app", ttl, args, ""))
        assertFalse(CommandSigning.verify(crypto, sender.publicKey, "open_app", ttl, args, "zz"))
    }

    @Test
    fun argOrderDoesNotMatter() {
        val sig = CommandSigning.sign(
            crypto, sender.privateKey, "open_app", ttl, linkedMapOf("b" to "2", "a" to "1")
        )
        // Sender iterated b,a — target rebuilds a,b via toSortedMap → same bytes.
        assertTrue(
            CommandSigning.verify(crypto, sender.publicKey, "open_app", ttl, mapOf("a" to "1", "b" to "2"), Hex.encode(sig))
        )
    }
}
