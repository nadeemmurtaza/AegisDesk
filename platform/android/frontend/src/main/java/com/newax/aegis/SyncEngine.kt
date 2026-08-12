package com.newax.aegis

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.sync.RoomJournalStore
import com.newax.aegis.memory.AgentMemory
import com.newax.aegis.sync.AntiEntropyRunner
import com.newax.aegis.sync.JvmLanTransport
import com.newax.aegis.sync.PeerEndpoint
import com.newax.aegis.sync.RelayTransport
import com.newax.aegis.sync.TransportConnection
import com.newax.aegis.sync.TransportListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The one Android sync engine (docs/SYNC_DESIGN.md §4.2) — a single
 * implementation of the anti-entropy cycle used by BOTH the periodic
 * [SyncWorker] (catch-up net, 15-min cadence) and the continuous
 * [SyncForegroundService] (item 7 — the transport stays UP so a peer can reach
 * this device at any moment, not only inside a worker window).
 *
 *  - [runCycle]: the original worker behaviour — start the LAN transport,
 *    let mDNS resolve, run outbound rounds, stop, then the relay phase (WAN).
 *  - [runContinuous]: the service behaviour — keep the LAN transport alive
 *    across outbound rounds every [OUTBOUND_INTERVAL_MS], reconnect the relay
 *    every [RELAY_RECONNECT_MS], back off and restart the transport on error.
 *
 * Both are idempotent (opId-deduped journal, watermark-advancing vectors), so
 * the worker and service can overlap harmlessly.
 */
object SyncEngine {

    /** What one cycle/round-trip produced — surfaced in the Sync screen status. */
    data class CycleResult(
        val lanPeers: Int,
        val relayPeers: Int,
        val entriesApplied: Int
    )

    private const val DISCOVERY_WAIT_MS = 8_000L
    private const val RELAY_WAIT_MS = 3_000L
    private const val OUTBOUND_INTERVAL_MS = 15_000L
    private const val RELAY_RECONNECT_MS = 60_000L
    private const val MIN_BACKOFF_MS = 5_000L
    private const val MAX_BACKOFF_MS = 300_000L

    // ── shared pieces ─────────────────────────────────────────────────────────

    private fun journalStore(): RoomJournalStore {
        val identity = SyncRuntime.identity()
        val db = AegisDatabase.get
        return RoomJournalStore(
            db.syncJournalDao(),
            db.syncVectorDao(),
            identity.identity.deviceId
        )
    }

    /** Start the LAN transport with inbound rounds wired to the accept thread. */
    private fun startLan(store: RoomJournalStore): JvmLanTransport {
        val transport = JvmLanTransport(SyncRuntime.identity(), SyncRuntime.keyStore(), SyncRuntime.crypto())
        transport.start(object : TransportListener {
            override fun onPeerDiscovered(endpoint: PeerEndpoint) = Unit
            override fun onPeerConnected(connection: TransportConnection) {
                runCatching {
                    syncRound(store, connection)
                    connection.close()
                }
            }
        })
        return transport
    }

    /** One anti-entropy round + materialize whatever arrived (opId diff). */
    private fun syncRound(store: RoomJournalStore, connection: TransportConnection): Int {
        val before = store.existingOpIds()
        AntiEntropyRunner.syncOnce(connection, store)
        val received = store.entries().filter { it.opId !in before }
        SyncRuntime.materialize(received)
        return received.size
    }

    /**
     * Outbound LAN rounds to every discovered + manually-addressed peer.
     * Returns (peers connected, entries applied) so callers can surface both.
     */
    private suspend fun lanOutbound(store: RoomJournalStore, transport: JvmLanTransport): Pair<Int, Int> {
        var peers = 0
        var applied = 0
        val targets = (transport.discoveredPeers() + SyncRuntime.manualEndpoints())
            .distinctBy { it.deviceId }
        for (endpoint in targets) {
            val connection = transport.connect(endpoint) ?: continue
            try {
                applied += syncRound(store, connection)
                peers++
            } finally {
                connection.close()
            }
        }
        return peers to applied
    }

    /**
     * The relay phase (WAN, docs/SYNC_DESIGN.md §10) — only when a URL is set;
     * LAN failures never block it. Returns (peers connected, entries applied)
     * so callers can surface both, exactly like the original worker counted
     * relayPeers (per successful outbound connect) and entriesApplied (per
     * round, inbound listener included).
     */
    private suspend fun relayPhase(store: RoomJournalStore, url: String): Pair<Int, Int> {
        var peers = 0
        var applied = 0
        try {
            val relay = RelayTransport(SyncRuntime.identity(), SyncRuntime.keyStore(), SyncRuntime.crypto(), url)
            relay.start(object : TransportListener {
                override fun onPeerDiscovered(endpoint: PeerEndpoint) = Unit
                override fun onPeerConnected(connection: TransportConnection) {
                    runCatching {
                        applied += syncRound(store, connection)
                        connection.close()
                    }
                }
            })
            try {
                // Presence fan-out needs a moment; inbound rounds run meanwhile.
                delay(RELAY_WAIT_MS)
                for (endpoint in relay.discoveredPeers().distinctBy { it.deviceId }) {
                    val connection = relay.connect(endpoint) ?: continue
                    try {
                        applied += syncRound(store, connection)
                        peers++
                    } finally {
                        connection.close()
                    }
                }
            } finally {
                relay.stop()
            }
        } catch (e: Exception) {
            SyncRuntime.recordStatus("Relay error: ${e.message ?: e.javaClass.simpleName}")
        }
        return peers to applied
    }

    // ── periodic mode (SyncWorker) ────────────────────────────────────────────

    /**
     * One full cycle: LAN (start → discovery window → outbound → stop), then
     * the relay phase. Throws on LAN-level failure so the caller decides the
     * retry policy (worker → Result.retry, service → backoff loop).
     */
    suspend fun runCycle(): CycleResult = withContext(Dispatchers.IO) {
        val store = journalStore()
        val transport = startLan(store)
        var applied = 0
        var lanPeers = 0
        try {
            // mDNS needs a few seconds to resolve; inbound rounds run meanwhile.
            delay(DISCOVERY_WAIT_MS)
            val (peers, entries) = lanOutbound(store, transport)
            lanPeers = peers
            applied += entries
        } finally {
            transport.stop()
        }
        var relayPeers = 0
        val relayUrl = SyncRuntime.relayUrl()
        if (relayUrl.isNotBlank()) {
            val (relayP, relayApplied) = relayPhase(store, relayUrl)
            relayPeers = relayP
            applied += relayApplied
        }
        // Background memory maintenance (docs/MEMORY_DESIGN.md): conflict
        // resolution + episodic→semantic consolidation + forgetting/decay run
        // with every periodic cycle, not just from the Distill button.
        runCatching { AgentMemory.distill() }

        // Surface the round exactly like the original worker did — the Sync
        // screen's status line reads this after each periodic cycle.
        SyncRuntime.recordStatus(
            when {
                lanPeers == 0 && relayPeers == 0 ->
                    "Scan complete — no peers found (LAN + relay)"
                else -> "Synced $lanPeers LAN + $relayPeers relay peer(s) · $applied new " +
                    (if (applied == 1) "entry" else "entries")
            }
        )
        CycleResult(lanPeers, relayPeers, applied)
    }

    // ── continuous mode (SyncForegroundService) ───────────────────────────────

    /**
     * The item-7 listening loop: the LAN transport stays UP between outbound
     * rounds (inbound connections are accepted at any time), the relay
     * reconnects periodically, and failures back off and restart the
     * transport. Runs until the calling coroutine is cancelled; when auto-sync
     * is turned off the loop idles (the Sync screen stops the service).
     */
    suspend fun runContinuous(): Unit = withContext(Dispatchers.IO) {
        var lan: JvmLanTransport? = null
        var backoff = MIN_BACKOFF_MS
        var relayLastMs = 0L
        while (true) {
            if (!runCatching { SyncRuntime.enabled() }.getOrDefault(true)) {
                SyncRuntime.recordStatus("Auto-sync off — sync service idle")
                delay(OUTBOUND_INTERVAL_MS)
                continue
            }
            try {
                val store = journalStore()
                if (lan == null) lan = startLan(store)
                val (_, applied) = lanOutbound(store, lan)
                val relayUrl = SyncRuntime.relayUrl()
                if (relayUrl.isNotBlank() && System.currentTimeMillis() - relayLastMs >= RELAY_RECONNECT_MS) {
                    relayPhase(store, relayUrl)
                    relayLastMs = System.currentTimeMillis()
                }
                // (relayPhase's result is unused in continuous mode — its own
                // status line is set below.)
                backoff = MIN_BACKOFF_MS
                SyncRuntime.recordStatus(
                    "listening · peers: ${lan.discoveredPeers().size} · applied $applied · " +
                        "journal ${store.entries().size}"
                )
            } catch (e: Exception) {
                lan?.stop()
                lan = null
                SyncRuntime.recordStatus("Sync error: ${e.message ?: e.javaClass.simpleName}")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            delay(OUTBOUND_INTERVAL_MS)
        }
    }
}
