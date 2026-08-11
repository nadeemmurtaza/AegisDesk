package com.newax.aegis.desktopsync

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.EntityAlias
import com.newax.aegis.db.entity.GraphEntity
import com.newax.aegis.db.entity.GraphPredicate
import com.newax.aegis.db.entity.PersonEntity
import com.newax.aegis.db.entity.PersonFactEntity
import com.newax.aegis.db.getAegisDatabase
import com.newax.aegis.db.sync.RoomJournalStore
import com.newax.aegis.db.sync.SyncPayload
import com.newax.aegis.sync.AntiEntropyRunner
import com.newax.aegis.sync.Identity
import com.newax.aegis.sync.JvmLanTransport
import com.newax.aegis.sync.PairedPeer
import com.newax.aegis.sync.Pairing
import com.newax.aegis.sync.PairingRequest
import com.newax.aegis.sync.PeerEndpoint
import com.newax.aegis.sync.StoredIdentity
import com.newax.aegis.sync.SyncEntry
import com.newax.aegis.sync.TransportConnection
import com.newax.aegis.sync.TransportListener
import com.newax.aegis.sync.platformCrypto
import com.newax.aegis.sync.platformKeyStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The desktop sync engine shared by both desktop bodies (Windows desktopApp and
 * macOS macosApp) — the JVM twin of Android's SyncRuntime + SyncWorker
 * (docs/SYNC_DESIGN.md §4.2). One object per process:
 *
 *  - device identity (load-or-generate via [platformKeyStore], dev FileKeyStore
 *    on the JVM),
 *  - the Room-backed journal (~/.aegis/sync.db, bundled sqlite) + the tested
 *    [JvmLanTransport]: inbound anti-entropy rounds on the accept thread,
 *    outbound rounds to every discovered peer every [LOOP_INTERVAL_MS],
 *  - memory-profile materialization to ~/.aegis/memory.json (the phone's
 *    encrypted memory profile converges here),
 *  - text-code pairing + canonical SAS (same rule as Android: the code with
 *    the lexicographically smaller sign-key hex is the initiator, so both
 *    devices show the same 6-digit code),
 *  - status/CLI surface: [status], [pairingCode], [memory], ...
 *
 * Desktop-originated syncable captures (goals etc.) land in a later slice —
 * today the desktop bodies sync what the phones journal.
 */
object DesktopSync {

    /** Journal table name for the memory profile — mirrors Android's SyncRuntime. */
    private const val TABLE_MEMORY_PROFILE = "memory_profile"

    private const val LOOP_INTERVAL_MS = 15_000L

    private val home: File
        get() = File(System.getProperty("user.home") ?: ".")
    private val memoryFile: File
        get() = File(home, ".aegis/memory.json")

    @Volatile
    private var running = false

    @Volatile
    private var lastStatus = "sync not started"

    @Volatile
    private var transport: JvmLanTransport? = null

    @Volatile
    private var store: RoomJournalStore? = null

    /** The opened Room DB — set in [start], used by materialize. */
    @Volatile
    private var database: AegisDatabase? = null

    @Volatile
    private var identityHolder: StoredIdentity? = null

    private val identityLock = Any()

    // ── lifecycle ────────────────────────────────────────────────────────────

    /** Start the automatic sync loop (daemon thread) — idempotent. */
    fun start(displayName: String = "Desktop " + System.getProperty("os.name")) {
        if (running) return
        running = true
        Thread {
            try {
                val me = this.identity(displayName)
                val db = getAegisDatabase(File(home, ".aegis/sync.db"))
                AegisDatabase.init(db)
                database = db
                val journalStore = RoomJournalStore(
                    db.syncJournalDao(),
                    db.syncVectorDao(),
                    me.identity.deviceId
                )
                store = journalStore
                val lan = JvmLanTransport(me, platformKeyStore(), platformCrypto())
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
                database = null
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

    // ── identity ─────────────────────────────────────────────────────────────

    private fun identity(displayName: String = "Desktop " + System.getProperty("os.name")): StoredIdentity {
        synchronized(identityLock) {
            identityHolder?.let { return it }
            val existing = platformKeyStore().loadIdentity()
            val created = existing
                ?: Identity.generate(platformCrypto(), displayName)
                    .also { platformKeyStore().saveIdentity(it) }
            identityHolder = created
            return created
        }
    }

    fun deviceId(): String = identity().identity.deviceId

    fun displayName(): String = identity().identity.displayName

    // ── status / surface ─────────────────────────────────────────────────────

    /** One-line loop status for the CLI and window cards. */
    fun status(): String = lastStatus

    fun peers(): List<PairedPeer> = platformKeyStore().pairedPeers()

    fun journalEntries(): Int = store?.entries()?.size ?: 0

    /** The synced memory profile: category → facts (from ~/.aegis/memory.json). */
    fun memory(): Map<String, List<String>> {
        val root = if (memoryFile.isFile) {
            runCatching { JSONObject(memoryFile.readText()) }.getOrNull() ?: return emptyMap()
        } else {
            return emptyMap()
        }
        val categories = root.optJSONObject("categories") ?: return emptyMap()
        return buildMap {
            categories.keys().forEach { key ->
                val array = categories.optJSONArray(key) ?: return@forEach
                put(
                    key,
                    buildList { for (i in 0 until array.length()) add(array.getString(i)) }
                )
            }
        }
    }

    fun memoryCategories(): List<String> = memory().keys.sorted()

    // ── pairing (text code + canonical SAS, no camera) ───────────────────────

    fun pairingCode(): String = Pairing.createRequest(platformCrypto(), identity().identity).encode()

    /**
     * The 6-digit SAS both devices must show — canonical initiator = the code
     * with the lexicographically smaller sign-key hex, so both sides converge
     * on one number. Null on a malformed code.
     */
    fun sasFor(myCode: String, theirCode: String): String? {
        val mine = PairingRequest.decode(myCode.trim()) ?: return null
        val theirs = PairingRequest.decode(theirCode.trim()) ?: return null
        val mySignHex = com.newax.aegis.sync.Hex.encode(mine.signPublicKey)
        val theirSignHex = com.newax.aegis.sync.Hex.encode(theirs.signPublicKey)
        return if (mySignHex <= theirSignHex) {
            Pairing.sas(mine.signPublicKey, theirs.signPublicKey, mine.nonce)
        } else {
            Pairing.sas(theirs.signPublicKey, mine.signPublicKey, theirs.nonce)
        }
    }

    /** Store the peer from their code (mutual — they pair with us the same way). */
    fun pairWith(code: String): PairedPeer? {
        val request = PairingRequest.decode(code.trim()) ?: return null
        return try {
            val me = identity()
            val peer = Pairing.confirmResponder(
                request = request,
                myDeviceId = me.identity.deviceId,
                mySignPublicKey = me.identity.signPublicKey,
                nowMs = System.currentTimeMillis()
            )
            platformKeyStore().savePeer(peer)
            peer
        } catch (_: Exception) {
            null
        }
    }

    fun unpair(deviceId: String) {
        platformKeyStore().removePeer(deviceId)
        peerAddressFile(deviceId).delete()
    }

    /** Manually-entered `host:port` for a paired peer (mDNS-free bootstrap). */
    fun peerAddress(deviceId: String): String? =
        peerAddressFile(deviceId).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun setPeerAddress(deviceId: String, address: String) {
        val file = peerAddressFile(deviceId)
        if (address.isBlank()) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            file.writeText(address)
        }
    }

    /** Endpoints for peers with a stored `host:port` — direct connect. */
    fun manualEndpoints(): List<PeerEndpoint> {
        val out = mutableListOf<PeerEndpoint>()
        for (peer in peers()) {
            val address = peerAddress(peer.deviceId) ?: continue
            val parts = address.split(":")
            val host = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: continue
            val port = parts.getOrNull(1)?.toIntOrNull() ?: continue
            out.add(PeerEndpoint(peer.deviceId, peer.displayName, host, port))
        }
        return out
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun peerAddressFile(deviceId: String): File =
        File(home, ".aegis/peer-addr-$deviceId.txt")

    private fun syncRound(journalStore: RoomJournalStore, connection: TransportConnection): Int {
        val before = journalStore.existingOpIds()
        AntiEntropyRunner.syncOnce(connection, journalStore)
        val received = journalStore.entries().filter { it.opId !in before }
        received.forEach { entry -> materialize(entry) }
        return received.size
    }

    private fun materialize(entry: SyncEntry) {
        if (entry.kind != SyncEntry.Kind.RECORD) return
        runCatching {
            when (entry.table) {
                TABLE_MEMORY_PROFILE -> if (!entry.tombstone) materializeMemory(entry)
                // Record tables (Slice 1): full-state LWW per natural key.
                "entities" -> materializeEntity(entry)
                "predicates" -> materializePredicate(entry)
                "entity_aliases" -> materializeAlias(entry)
                "persons" -> materializePerson(entry)
                "person_facts" -> materializePersonFact(entry)
                else -> Unit
            }
        }
    }

    private fun materializeMemory(entry: SyncEntry) {
        val array = JSONArray(entry.payload.decodeToString())
        val facts = buildList {
            for (i in 0 until array.length()) add(array.getString(i))
        }
        applyMemoryProfile(entry.key, facts)
    }

    /**
     * LWW guard — mirror of SyncRuntime.locallyNewer: skip an incoming RECORD
     * when the local journal already holds a strictly newer entry for the same
     * (table, key), ordered by (hlcWall, hlcCounter, deviceId).
     */
    private fun locallyNewer(entry: SyncEntry): Boolean {
        val db = database ?: return false
        return runCatching {
            val latest = kotlinx.coroutines.runBlocking {
                db.syncJournalDao().latestFor(entry.table, entry.key)
            } ?: return@runCatching false
            (latest.hlcWall > entry.hlc.wall) ||
                (latest.hlcWall == entry.hlc.wall && latest.hlcCounter > entry.hlc.counter) ||
                (latest.hlcWall == entry.hlc.wall && latest.hlcCounter == entry.hlc.counter &&
                    latest.deviceId > entry.deviceId)
        }.getOrDefault(false)
    }

    private fun materializeEntity(entry: SyncEntry) {
        if (entry.tombstone || locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            if (db.graphDao().findByName(name) != null) return@runBlocking
            db.graphDao().insertEntity(
                GraphEntity(
                    type = fields["type"]?.toIntOrNull() ?: 0,
                    canonicalName = name,
                    createdAt = fields["createdAt"]?.toLongOrNull() ?: entry.createdAt
                )
            )
        }
    }

    private fun materializePredicate(entry: SyncEntry) {
        if (entry.tombstone || locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            if (db.graphDao().predicateByName(name) != null) return@runBlocking
            db.graphDao().insertPredicate(GraphPredicate(name = name))
        }
    }

    private fun materializeAlias(entry: SyncEntry) {
        if (entry.tombstone || locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val alias = fields["alias"]?.takeIf { it.isNotBlank() } ?: return
        val entityName = fields["entityName"]?.takeIf { it.isNotBlank() } ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            val dao = db.graphDao()
            if (dao.findEntityByAlias(alias) != null) return@runBlocking
            val entityId = dao.findByName(entityName)?.id ?: return@runBlocking
            dao.insertAlias(EntityAlias(entityId = entityId, alias = alias))
        }
    }

    private fun materializePerson(entry: SyncEntry) {
        if (entry.tombstone || locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            val dao = db.personDao()
            val existing = dao.findByName(name)
            if (existing != null) {
                dao.updateStats(
                    existing.id,
                    fields["sourceCount"]?.toIntOrNull() ?: existing.sourceCount,
                    fields["totalMentions"]?.toIntOrNull() ?: existing.totalMentions,
                    fields["importanceScore"]?.toFloatOrNull() ?: existing.importanceScore,
                    fields["lastSeenMs"]?.toLongOrNull() ?: existing.lastSeenMs
                )
                if (fields["profileBuilt"] == "true") dao.markProfileBuilt(name)
            } else {
                dao.insertIfAbsent(
                    PersonEntity(
                        name = name,
                        importanceScore = fields["importanceScore"]?.toFloatOrNull() ?: 0f,
                        sourceCount = fields["sourceCount"]?.toIntOrNull() ?: 0,
                        totalMentions = fields["totalMentions"]?.toIntOrNull() ?: 0,
                        lastSeenMs = fields["lastSeenMs"]?.toLongOrNull() ?: 0L,
                        profileBuilt = fields["profileBuilt"] == "true"
                    )
                )
            }
        }
    }

    private fun materializePersonFact(entry: SyncEntry) {
        if (entry.tombstone || locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val personName = fields["personName"]?.takeIf { it.isNotBlank() } ?: return
        val fact = fields["fact"]?.takeIf { it.isNotBlank() } ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            val personDao = db.personDao()
            val personId = personDao.findByName(personName)?.id
                ?: personDao.insertIfAbsent(PersonEntity(name = personName)).let {
                    personDao.idForName(personName) ?: return@runBlocking
                }
            if (db.personFactDao().findExact(personId, fact) != null) return@runBlocking
            db.personFactDao().insert(
                PersonFactEntity(
                    personId = personId,
                    fact = fact,
                    category = fields["category"] ?: "",
                    confidence = fields["confidence"]?.toFloatOrNull() ?: 0.7f,
                    source = fields["source"] ?: "",
                    timestampMs = fields["timestampMs"]?.toLongOrNull() ?: 0L
                )
            )
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
