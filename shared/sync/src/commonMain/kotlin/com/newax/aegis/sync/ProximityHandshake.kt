package com.newax.aegis.sync

/**
 * The pre-transfer key exchange for the encrypted Quick Share
 * (docs/SYNC_DESIGN.md §10.1): both sides swap their long-term ECDH public
 * keys over the [TransferChannel] so [ProximityTransfer.send] can seal the
 * INIT blob to the recipient's key. Public keys only — nothing secret leaves
 * the device, and [ProximityTransfer] itself is unchanged ("anonymous on the
 * wire": proximity v1 does not require prior mesh pairing; the receiver's
 * user-confirmation gate is the trust anchor, like Quick Share).
 *
 * Both sides call [exchangeKeys] before starting their [ProximityTransfer]
 * role. The marker byte disambiguates a key message from a sealed-blob
 * message — a blob is hex text (0x30–0x39 / 0x61–0x66), never 0x4B — so a
 * mixed stream cannot misroute. Write-then-read ordering is safe on any
 * message channel: both writes land before either read consumes them.
 */
object ProximityHandshake {

    /** 'K' — never the first byte of a ProximityTransfer sealed blob. */
    const val KEY_MARKER = 0x4B

    const val TIMEOUT_MS = 30_000L

    const val MAX_KEY_BYTES = 128

    /**
     * Send [myPublicKey] and return the peer's public key; null on timeout,
     * closed channel, or a non-key message (named failure — the caller
     * aborts the transfer with a Failed("handshake", ...) result).
     */
    fun exchangeKeys(
        channel: TransferChannel,
        myPublicKey: ByteArray,
        timeoutMs: Long = TIMEOUT_MS
    ): ByteArray? {
        if (myPublicKey.isEmpty() || myPublicKey.size > MAX_KEY_BYTES) return null
        val out = ByteArray(1 + myPublicKey.size)
        out[0] = KEY_MARKER
        myPublicKey.copyInto(out, 1)
        if (!channel.write(out)) return null
        val reply = channel.read(timeoutMs) ?: return null
        if (reply.isEmpty() || reply[0] != KEY_MARKER) return null
        val key = reply.copyOfRange(1, reply.size)
        if (key.isEmpty() || key.size > MAX_KEY_BYTES) return null
        return key
    }
}
