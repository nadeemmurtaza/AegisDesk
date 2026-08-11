package com.newax.aegis.sync

/**
 * Per-peer high-water marks — the version vector answering "what do you have
 * that I don't?" (docs/SYNC_DESIGN.md §4.2). One [Hlc] per peer: the highest
 * journal position this device has applied from that peer.
 *
 * Diffing: to sync with peer P, send every journal entry with
 * `hlc > watermarks[P]` (no row = [Hlc.ZERO] = send everything).
 */
data class VersionVector(
    val watermarks: Map<String, Hlc> = emptyMap()
) {
    /** Advance (or set) the watermark for [peerDeviceId]. */
    fun advance(peerDeviceId: String, hlc: Hlc): VersionVector {
        val current = watermarks[peerDeviceId] ?: Hlc.ZERO
        return VersionVector(watermarks + (peerDeviceId to maxOf(current, hlc)))
    }

    /** Watermark for [peerDeviceId] (ZERO when never synced). */
    fun watermarkFor(peerDeviceId: String): Hlc = watermarks[peerDeviceId] ?: Hlc.ZERO

    /** Union with another vector — per-peer max (both directions of a sync). */
    fun merge(other: VersionVector): VersionVector {
        val merged = watermarks.toMutableMap()
        other.watermarks.forEach { (peer, hlc) ->
            val current = merged[peer] ?: Hlc.ZERO
            if (hlc > current) merged[peer] = hlc
        }
        return VersionVector(merged)
    }

    /** True when this vector is at least as far as [other] on every peer. */
    fun dominates(other: VersionVector): Boolean =
        other.watermarks.all { (peer, hlc) -> watermarkFor(peer) >= hlc }

    companion object {
        val EMPTY = VersionVector()
    }
}
