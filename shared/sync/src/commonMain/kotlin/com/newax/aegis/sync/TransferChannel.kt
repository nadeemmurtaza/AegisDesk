package com.newax.aegis.sync

/**
 * A reliable, ordered, message-based byte channel for the encrypted proximity
 * transfer (docs/SYNC_DESIGN.md §10.1). [ProximityTransfer] runs over any
 * implementation: a TCP socket on a WiFi-Direct group, a plain LAN socket,
 * a Bluetooth channel, or an in-memory pipe in tests. One [write] is
 * delivered whole to the peer's next [read] — the channel owns framing, the
 * protocol owns crypto.
 *
 * Implementations must not throw from [read]/[write]: named failure modes
 * are returned (null / false), and the caller surfaces them as explicit
 * [ProximityTransfer.Result.Failed] stages.
 */
interface TransferChannel {

    /** Next message, blocking up to [timeoutMs]; null on timeout/EOF/closed. */
    fun read(timeoutMs: Long): ByteArray?

    /** One whole message; false when the channel is closed or the write failed. */
    fun write(message: ByteArray): Boolean

    fun close()
}
