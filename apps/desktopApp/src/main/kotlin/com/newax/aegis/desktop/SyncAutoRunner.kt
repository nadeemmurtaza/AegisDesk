package com.newax.aegis.desktop

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.getAegisDatabase
import com.newax.aegis.db.sync.RoomJournalStore
import com.newax.aegis.sync.AntiEntropyRunner
import com.newax.aegis.sync.JvmLanTransport
import com.newax.aegis.sync.PeerEndpoint
import com.newax.aegis.sync.SyncEntry
import com.newax.aegis.sync.TransportConnection
import com.newax.aegis.sync.TransportListener
import com.newax.aegis.sync.platformCrypto
import com.newax.aegis.sync.platformKeyStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The desktop automatic sync loop (docs/SYNC_DESIGN.md §4.2) — started at app
 * launch in both window and CLI mode. Opens the Room-backed journal
 * (~/.aegis/sync.db, bundled sqlite — the desktopMain DB actual), runs the
 * tested [JvmLanTransport], serves inbound anti-entropy rounds on its accept
 * thread, and periodically connects out to every discovered peer. Incoming
 * `memory_profile` records (the Android app's encrypted memory profile) are
 * materialized to ~/.aegis/memory.json so the desktop converges on the same
 * user facts. Status is surfaced via [status] (CLI `sync` + window Status card).
 *
 * Desktop-originated syncable captures (goals etc.) land in a later slice —
 * today the desktop syncs what the phone journals.
 */
object SyncAutoRunner {

    /** Journal table name for the memory profile — mirrors Android's SyncRuntime. */
    private const val TABLE_MEMORY_PROFILE = "memory_profile"

    private const val LOOP_INTERVAL_MS = 15_000L

    private val memoryFile = File(
        System.getProperty("user.home") ?: ".",
        ".aegis/memory.json"
    )

    @Volatile
    private var running = false

    @Volatile
    private var lastStatus = "sync not started"

    @Volatile
    private var transport: JvmLanTransport? = null

    @Volatile
    private var store: RoomJournalStore? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val db = getAegisDatabase(File(System.getProperty("user.home") ?: ".", ".aegis/sync.db"))
                AegisDatabase.init(db)
                val identity = ProximityCli.identity()
                val journalStore = RoomJournalStore(
                    db.syncJournalDao(),
                    db.syncVectorDao(),
                    identity.identity.deviceId
                )
                store = journalStore
                val lan = JvmLanTransport(identity, platformKeyStore(), platformCrypto())
                transport = lan
                lan.start(object : TransportListener {
                    override fun onPeerDiscovered(endpoint: PeerEndpoint) = Unit
                    override fun onPeerConnected(connection: TransportConnection) {
                        runCatching {
                            syncRound(journalStore, connection)
                            connection.close()
                        }
                    }
                })
                lastStatus = "listening on ${lan.boundPort} · " +
                    (lan.mdnsError?.let { "mDNS: $it" } ?: "mDNS on")
                while (running) {
                    var synced = 0
                    var applied = 0
                    for (endpoint in lan.discoveredPeers()) {
                        val connection = lan.connect(endpoint) ?: continue
                        try {
                            applied += syncRound(journalStore, connection)
                            synced++
                        } finally {
                            connection.close()
                        }
                    }
                    lastStatus =
                        "peers: ${lan.discoveredPeers().size} · synced $synced · applied $applied · " +
                            "journal ${journalStore.entries().size} · memory ${memoryCategories().size} category(ies)"
                    Thread.sleep(LOOP_INTERVAL_MS)
                }
            } catch (e: Exception) {
                lastStatus = "sync error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                running = false
                transport?.stop()
                transport = null
                store = null
            }
        }.apply {
            isDaemon = true
            name = "aegis-sync-auto"
            start()
        }
    }

    fun stop() {
        running = false
    }

    /** One-line status for the CLI `sync` command and the Status card. */
    fun status(): String = lastStatus

    /** The device identity — same store the Quick Share CLI uses. */
    fun deviceId(): String = ProximityCli.identity().identity.deviceId

    fun displayName(): String = ProximityCli.identity().identity.displayName

    fun peers(): Int = ProximityCli.identity().let { platformKeyStore().pairedPeers().size }

    fun journalEntries(): Int = store?.entries()?.size ?: 0

    fun memoryCategories(): List<String> {
        val root = if (memoryFile.isFile) {
            runCatching { JSONObject(memoryFile.readText()) }.getOrNull() ?: return emptyList()
        } else {
            return emptyList()
        }
        val categories = root.optJSONObject("categories") ?: return emptyList()
        return buildList {
            categories.keys().forEach { add(it) }
        }.sorted()
    }

    private fun syncRound(journalStore: RoomJournalStore, connection: TransportConnection): Int {
        val before = journalStore.existingOpIds()
        AntiEntropyRunner.syncOnce(connection, journalStore)
        val received = journalStore.entries().filter { it.opId !in before }
        received.forEach { entry -> materialize(entry) }
        return received.size
    }

    private fun materialize(entry: SyncEntry) {
        if (entry.kind != SyncEntry.Kind.RECORD) return
        if (entry.table != TABLE_MEMORY_PROFILE || entry.tombstone) return
        runCatching {
            val array = JSONArray(entry.payload.decodeToString())
            val facts = buildList {
                for (i in 0 until array.length()) add(array.getString(i))
            }
            applyMemoryProfile(entry.key, facts)
        }
    }

    private fun applyMemoryProfile(category: String, facts: List<String>) {
        val root = if (memoryFile.isFile) {
            runCatching { JSONObject(memoryFile.readText()) }.getOrNull() ?: JSONObject()
        } else {
            JSONObject()
        }
        val categories = root.optJSONObject("categories")
            ?: JSONObject().also { root.put("categories", it) }
        categories.put(category, JSONArray().apply { facts.forEach { put(it) } })
        memoryFile.parentFile?.mkdirs()
        memoryFile.writeText(root.toString())
    }
}
