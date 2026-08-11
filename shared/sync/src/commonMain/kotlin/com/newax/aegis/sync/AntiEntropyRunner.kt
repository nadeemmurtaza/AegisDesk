package com.newax.aegis.sync

/**
 * Drives one anti-entropy round over an established [TransportConnection]
 * (docs/SYNC_DESIGN.md §4.2, §9). The protocol is symmetric — both peers run
 * the same sequence, so a single implementation converges two journals over
 * any transport (LAN or relayed):
 *
 *   VECTOR_EXCH → DELTA → apply → ACK_VECTOR, both directions.
 *
 * The runner is pure sequencing over the [JournalStore] seam: no threads, no
 * IO types, no crypto — the connection already sealed/unsealed every message
 * during the S2 handshake. Idempotent by construction (opId dedup), safe to
 * run repeatedly on the same connection.
 */
object AntiEntropyRunner {

    enum class Outcome {
        /** Both directions exchanged, applied, and acked. */
        COMPLETED,
        /** The peer did not answer within [timeoutMs] (or the connection ended). */
        TIMEOUT,
        /** A message arrived out of the expected sequence. */
        PROTOCOL_ERROR,
        /** A send failed — the connection is gone. */
        CONNECTION_LOST
    }

    const val DEFAULT_TIMEOUT_MS = 30_000L

    /**
     * Run one full round. [timeoutMs] bounds each blocking receive — pass a
     * small value in tests so failures fail fast instead of hanging.
     */
    fun syncOnce(
        connection: TransportConnection,
        store: JournalStore,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): SyncRoundResult {
        val myId = store.myDeviceId()

        // 1 — exchange version vectors: what I have for each peer, theirs for me.
        if (!connection.send(WireCodec.SyncMessage.VectorExchange(myId, store.watermarks()))) {
            return SyncRoundResult(Outcome.CONNECTION_LOST)
        }
        val theirs = connection.receive(timeoutMs) ?: return SyncRoundResult(Outcome.TIMEOUT)
        val theirVector = theirs as? WireCodec.SyncMessage.VectorExchange
            ?: return SyncRoundResult(Outcome.PROTOCOL_ERROR)
        if (theirVector.deviceId != connection.peerDeviceId) {
            return SyncRoundResult(Outcome.PROTOCOL_ERROR)
        }

        // 2 — my delta for them: everything after their watermark for me.
        val theirWatermarkForMe = theirVector.watermarks[myId] ?: Hlc.ZERO
        val myDelta = AntiEntropy.buildDelta(myId, store.entries(), theirWatermarkForMe)
        if (!connection.send(myDelta)) return SyncRoundResult(Outcome.CONNECTION_LOST)

        // 3 — their delta for me: dedup-apply, persist, advance my watermark.
        val theirDeltaMsg = connection.receive(timeoutMs) ?: return SyncRoundResult(Outcome.TIMEOUT)
        val theirDelta = theirDeltaMsg as? WireCodec.SyncMessage.Delta
            ?: return SyncRoundResult(Outcome.PROTOCOL_ERROR)
        if (theirDelta.deviceId != connection.peerDeviceId) {
            return SyncRoundResult(Outcome.PROTOCOL_ERROR)
        }
        val applied = AntiEntropy.applyDelta(store.existingOpIds(), theirDelta.entries, theirDelta.deviceId)
        store.append(applied.newEntries)
        store.setWatermark(theirDelta.deviceId, applied.senderWatermark.watermarkFor(theirDelta.deviceId))

        // 4 — ack: my new watermark for them (they may GC up to here), and hear theirs.
        if (!connection.send(
                WireCodec.SyncMessage.AckVector(myId, store.watermarkFor(theirDelta.deviceId))
            )
        ) {
            return SyncRoundResult(Outcome.CONNECTION_LOST)
        }
        val theirAckMsg = connection.receive(timeoutMs) ?: return SyncRoundResult(Outcome.TIMEOUT)
        val theirAck = theirAckMsg as? WireCodec.SyncMessage.AckVector
            ?: return SyncRoundResult(Outcome.PROTOCOL_ERROR)
        if (theirAck.deviceId != connection.peerDeviceId) {
            return SyncRoundResult(Outcome.PROTOCOL_ERROR)
        }

        return SyncRoundResult(
            outcome = Outcome.COMPLETED,
            receivedEntries = applied.newEntries.size,
            duplicates = applied.duplicates,
            myWatermarkForPeer = store.watermarkFor(theirDelta.deviceId),
            peerAckedHlc = theirAck.ackedHlc
        )
    }

    /**
     * The ready-made inbound wiring: hand every accepted connection to the
     * sync round (R6 — the runner has a caller). Apps pass this as the
     * [TransportListener] for the transport; the wiring slice will chain the
     * discovered-endpoint events into the pairing/settings UI.
     */
    fun syncListenerFor(store: JournalStore): TransportListener = object : TransportListener {
        override fun onPeerDiscovered(endpoint: PeerEndpoint) = Unit
        override fun onPeerConnected(connection: TransportConnection) {
            syncOnce(connection, store)
        }
    }
}

/** The outcome of one [AntiEntropyRunner.syncOnce] round. */
data class SyncRoundResult(
    val outcome: AntiEntropyRunner.Outcome,
    /** Entries I applied from the peer this round. */
    val receivedEntries: Int = 0,
    /** Entries the peer sent that I already had (opId dedup). */
    val duplicates: Int = 0,
    /** My watermark for the peer after applying their delta. */
    val myWatermarkForPeer: Hlc = Hlc.ZERO,
    /** The peer's ack — how far they have applied MY journal (GC hint). */
    val peerAckedHlc: Hlc = Hlc.ZERO
) {
    val completed: Boolean
        get() = outcome == AntiEntropyRunner.Outcome.COMPLETED
}
