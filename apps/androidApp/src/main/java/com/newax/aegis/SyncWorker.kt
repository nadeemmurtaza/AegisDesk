package com.newax.aegis

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.sync.RoomJournalStore
import com.newax.aegis.sync.AntiEntropyRunner
import com.newax.aegis.sync.JvmLanTransport
import com.newax.aegis.sync.PeerEndpoint
import com.newax.aegis.sync.TransportConnection
import com.newax.aegis.sync.TransportListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The automatic sync loop on Android (docs/SYNC_DESIGN.md §4.2 — "whenever any
 * 2 of 4 are online they exchange changes"): a periodic WorkManager job that
 * runs the LAN transport against the Room-backed journal.
 *
 *  - inbound rounds run on the transport's accept thread via the listener,
 *  - outbound rounds connect to every discovered peer (mDNS) plus every peer
 *    with a manually-stored `host:port` (direct-connect bootstrap when mDNS
 *    is blocked),
 *  - a short discovery window lets mDNS resolve before we connect out.
 *
 * No-op until at least one peer is paired (the handshake rejects unpaired
 * devices), so the default-on setting is harmless before pairing.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!SyncRuntime.enabled()) return Result.success()
        return withContext(Dispatchers.IO) {
            try {
                val db = AegisDatabase.get
                val identity = SyncRuntime.identity()
                val store = RoomJournalStore(db.syncJournalDao(), db.syncVectorDao(), identity.identity.deviceId)
                val transport = JvmLanTransport(identity, SyncRuntime.keyStore(), SyncRuntime.crypto())
                transport.start(object : TransportListener {
                    override fun onPeerDiscovered(endpoint: PeerEndpoint) = Unit
                    override fun onPeerConnected(connection: TransportConnection) {
                        runCatching {
                            syncRound(store, connection)
                            connection.close()
                        }
                    }
                })
                try {
                    // mDNS needs a few seconds to resolve; inbound rounds run meanwhile.
                    delay(DISCOVERY_WAIT_MS)
                    val targets = (transport.discoveredPeers() + SyncRuntime.manualEndpoints())
                        .distinctBy { it.deviceId }
                    var peersSynced = 0
                    var entriesApplied = 0
                    for (endpoint in targets) {
                        val connection = transport.connect(endpoint) ?: continue
                        try {
                            entriesApplied += syncRound(store, connection)
                            peersSynced++
                        } finally {
                            connection.close()
                        }
                    }
                    SyncRuntime.recordStatus(
                        if (peersSynced == 0) "Scan complete — no peers found"
                        else "Synced $peersSynced peer(s) · $entriesApplied new " +
                            (if (entriesApplied == 1) "entry" else "entries")
                    )
                } finally {
                    transport.stop()
                }
                Result.success()
            } catch (e: Exception) {
                SyncRuntime.recordStatus("Sync error: ${e.message ?: e.javaClass.simpleName}")
                Result.retry()
            }
        }
    }

    /** One anti-entropy round + materialize whatever arrived (opId diff). */
    private fun syncRound(store: RoomJournalStore, connection: TransportConnection): Int {
        val before = store.existingOpIds()
        AntiEntropyRunner.syncOnce(connection, store)
        val received = store.entries().filter { it.opId !in before }
        SyncRuntime.materialize(received)
        return received.size
    }

    private companion object {
        const val DISCOVERY_WAIT_MS = 8_000L
    }
}
