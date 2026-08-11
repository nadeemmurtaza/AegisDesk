package com.newax.aegis.sync

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pre-transfer key exchange over cross-wired in-memory channels: both
 * sides swap ECDH public keys, and a non-key message (no marker byte) is
 * rejected instead of being mistaken for a key.
 */
class ProximityHandshakeTest {

    @Test
    fun bothSidesExchangeKeys() {
        val (a, b) = InMemoryTransferChannel.pipe()
        val keyA = ByteArray(32) { it.toByte() }
        val keyB = ByteArray(32) { (it + 64).toByte() }
        val aKey = AtomicReference<ByteArray?>()
        val bKey = AtomicReference<ByteArray?>()
        val tA = Thread { aKey.set(ProximityHandshake.exchangeKeys(a, keyA)) }.apply { start() }
        val tB = Thread { bKey.set(ProximityHandshake.exchangeKeys(b, keyB)) }.apply { start() }
        tA.join(5_000)
        tB.join(5_000)
        assertTrue(!tA.isAlive && !tB.isAlive, "both exchanges must complete")
        assertContentEquals(keyB, aKey.get(), "A learns B's key")
        assertContentEquals(keyA, bKey.get(), "B learns A's key")
    }

    @Test
    fun nonKeyMessageIsRejected() {
        val (a, b) = InMemoryTransferChannel.pipe()
        val keyA = ByteArray(32) { 7 }
        val aResult = AtomicReference<ByteArray?>()
        val tA = Thread { aResult.set(ProximityHandshake.exchangeKeys(a, keyA)) }.apply { start() }
        // B replies with a plain (non-marker) message — must not parse as a key.
        assertTrue(b.write("not-a-key-message".encodeToByteArray()))
        tA.join(5_000)
        assertNull(aResult.get(), "a non-key reply must fail the exchange")
    }

    @Test
    fun timeoutReturnsNull() {
        val (a, b) = InMemoryTransferChannel.pipe()
        val keyA = ByteArray(32) { 3 }
        val start = System.currentTimeMillis()
        // B never writes — A must time out and return null.
        val result = ProximityHandshake.exchangeKeys(a, keyA, timeoutMs = 300)
        assertNull(result)
        assertTrue(System.currentTimeMillis() - start >= 280)
        b.close()
    }
}
