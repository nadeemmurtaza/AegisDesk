package com.newax.aegis.sync

/**
 * The anti-entropy loop (docs/SYNC_DESIGN.md §4.2), as pure functions: on any
 * peer connection — LAN direct or relayed — both sides compute what the other
 * is missing from their version vectors, exchange DELTAs, dedup-apply, and
 * advance watermarks. Idempotent by construction; safe to run repeatedly.
 */
object AntiEntropy {

    /**
     * Everything I should send peer P: every journal entry strictly after P's
     * watermark for me (no watermark row = [Hlc.ZERO] = send the full journal —
     * the "safety net" of §4.2; the transport slice may bound the backlog).
     */
    fun outboundDelta(journal: List<SyncEntry>, peerWatermark: Hlc): List<SyncEntry> =
        journal.filter { it.hlc > peerWatermark }

    /**
     * Apply an incoming DELTA: dedup against [existingOpIds] (opId CRDT), and
     * advance my watermark for the sender to the max hlc actually applied.
     * Pure — the caller persists the new entries (wiring slice's
     * SyncJournalDao) and stores the returned vector.
     */
    fun applyDelta(
        existingOpIds: Set<String>,
        incoming: List<SyncEntry>,
        senderDeviceId: String
    ): ApplyResult {
        val merged = JournalMerge.merge(existingOpIds, incoming)
        val watermark = merged.newEntries.maxOfOrNull { it.hlc } ?: Hlc.ZERO
        return ApplyResult(
            newEntries = merged.newEntries,
            duplicates = merged.duplicates,
            senderWatermark = VersionVector.EMPTY.advance(senderDeviceId, watermark)
        )
    }

    /**
     * Resolve a record's live state from its full journal history — the
     * materialization step for RECORD tables (LWW + tombstone).
     */
    fun resolveRecord(history: List<SyncEntry>): RecordState = RecordResolver.resolve(history)

    /** Build the DELTA wire message for a peer given my journal + vectors. */
    fun buildDelta(deviceId: String, journal: List<SyncEntry>, peerWatermark: Hlc): WireCodec.SyncMessage.Delta =
        WireCodec.SyncMessage.Delta(
            deviceId = deviceId,
            fromHlc = peerWatermark,
            entries = outboundDelta(journal, peerWatermark)
        )
}

data class ApplyResult(
    /** Entries new to this device, in (hlc, deviceId) order. */
    val newEntries: List<SyncEntry>,
    /** Entries skipped as already-present. */
    val duplicates: Int,
    /** The sender's watermark after this apply — persist to sync_vector. */
    val senderWatermark: VersionVector
)
