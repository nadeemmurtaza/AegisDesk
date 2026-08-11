package com.newax.aegis.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-shot user-confirmation gate for an incoming proximity transfer
 * (Quick Share's accept/reject prompt). [ProximityTransfer.receive] takes a
 * synchronous accept callback, so the transfer thread calls [await] (which
 * blocks, like the callback demands); the UI thread calls [answer] when the
 * user decides. Timing out returns false (declined) so a stranded transfer
 * never hangs its channel — same failure mode as a user decline.
 */
class TransferGate(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }

    private val latch = CountDownLatch(1)

    @Volatile
    private var accepted = false

    /** Blocks until [answer] or the timeout; false on timeout/decline. */
    fun await(): Boolean {
        val released = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        return released && accepted
    }

    fun answer(accept: Boolean) {
        accepted = accept
        latch.countDown()
    }
}
