package com.newax.aegis.desktopsync

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.AppRecord
import com.newax.aegis.db.entity.EntityAlias
import com.newax.aegis.db.entity.GraphEdge
import com.newax.aegis.db.entity.GraphEntity
import com.newax.aegis.db.entity.GraphPredicate
import com.newax.aegis.db.entity.PersonEntity
import com.newax.aegis.db.entity.PersonFactEntity
import com.newax.aegis.db.entity.TriggerRule
import com.newax.aegis.db.getAegisDatabase
import com.newax.aegis.db.sync.RoomJournalStore
import com.newax.aegis.db.sync.SyncPayload
import com.newax.aegis.db.sync.toEntity
import com.newax.aegis.sync.CommandSigning
import com.newax.aegis.sync.Hlc
import com.newax.aegis.sync.AntiEntropyRunner
import com.newax.aegis.sync.Hex
import com.newax.aegis.sync.Identity
import com.newax.aegis.sync.JvmLanTransport
import com.newax.aegis.sync.PairedPeer
import com.newax.aegis.sync.Pairing
import com.newax.aegis.sync.Pairing.PairingRequest
import com.newax.aegis.sync.PeerEndpoint
import com.newax.aegis.sync.StoredIdentity
import com.newax.aegis.sync.SyncEntry
import com.newax.aegis.sync.SyncPolicy
import com.newax.aegis.sync.TransportConnection
import com.newax.aegis.sync.TransportListener
import com.newax.aegis.sync.platformCrypto
import com.newax.aegis.sync.platformKeyStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The desktop sync engine shared by both desktop bodies (the Windows and
 * macOS frontends) — the JVM twin of Android's SyncRuntime + SyncWorker
 * (docs/SYNC_DESIGN.md §4.2). One object per process:
 *
 *  - device identity (load-or-generate via [platformKeyStore] — OsKeyStore:
 *    DPAPI on Windows, Keychain on macOS, FileKeyStore fallback on Linux),
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

    /**
     * Pairing/revocation records — mirrors Android's SyncRuntime (same table
     * name and key shape `$myDeviceId\u0001$peerDeviceId`, so both platforms'
     * trust rows live in one journal namespace).
     */
    private const val TABLE_PEER_TRUST = "peer_trust"
    private const val TRUST_SEP = "\u0001"

    /** Per-category toggles + per-peer permissions — mirror of SyncRuntime. */
    private const val CAT_PREFIX = "sync:cat:"
    private const val PEER_PERM_PREFIX = "sync:peerperm:"

    /** Fabric journal tables (item 1) + the command inbox (item 6) — names match schema v13. */
    private const val TABLE_EDGES = "edges"
    private const val TABLE_APP_RECORDS = "app_records"
    private const val TABLE_APP_CAPABILITY_LINKS = "app_capability_links"
    private const val TABLE_TRIGGER_RULES = "trigger_rules"
    private const val TABLE_KV_STORE = "kv_store"
    private const val TABLE_COMMANDS = "commands"

    /** The three synced layers of the hierarchical agent memory (schema v14). */
    private const val TABLE_EPISODES = "episodes"
    private const val TABLE_HANDOFFS = "handoffs"
    private const val TABLE_LIBRARY_ENTRIES = "library_entries"
    private const val EDGE_SEP = "\u0001"
    private const val COMMAND_TARGET_PREFIX = "to:"
    private const val COMMAND_ACK_PREFIX = "ack:"
    private const val DEFAULT_COMMAND_TTL_MS = 24 * 60 * 60 * 1000L

    /**
     * The four user-facing sync categories → journal table names (mirror of
     * SyncRuntime.CATEGORY_TABLES; same keys so a policy written on one
     * platform reads on the other). `peer_trust` is never gated.
     */
    val CATEGORY_TABLES: Map<String, List<String>> = linkedMapOf(
        "Memory profile" to listOf(TABLE_MEMORY_PROFILE),
        "Knowledge graph" to listOf("entities", "predicates", "entity_aliases"),
        "People" to listOf("persons", "person_facts"),
        "Settings & preferences" to listOf("kv_store")
    )

    /** Command classes a peer may send (design §6); empty set = unrestricted. */
    val COMMAND_CLASSES = listOf(
        "send_email", "open_app", "open_file", "browse_files",
        "run_goal", "system_query", "run_shell"
    )

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
    private var commandDispatcher: ((SyncEntry) -> Unit)? = null

    /**
     * Fix C — the desktop frontend body registers its CommandDispatcher here so
     * DesktopSync (shared) can hand it incoming targeted commands. One
     * dispatcher per process, first registration wins.
     */
    fun setCommandDispatcher(dispatcher: (SyncEntry) -> Unit) {
        synchronized(identityLock) {
            if (commandDispatcher == null) commandDispatcher = dispatcher
        }
    }

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

    private val hlcLock = Any()
    private var clock = Hlc(System.currentTimeMillis(), 0)

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

    /** Monotonic per-process HLC for locally-captured entries. */
    private fun nextHlc(): Hlc = synchronized(hlcLock) {
        clock = Hlc.tick(clock, System.currentTimeMillis())
        clock
    }

    // ── sync policy (per-category toggles + per-peer permissions) ───────────

    fun categoryEnabled(table: String): Boolean = kvGet(CAT_PREFIX + table) != "0"

    fun setCategoryEnabled(table: String, on: Boolean) {
        if (on) kvDelete(CAT_PREFIX + table) else kvPut(CAT_PREFIX + table, "0")
    }

    /** Grouped category states for the UI/CLI: category → enabled. */
    fun categories(): List<Pair<String, Boolean>> =
        CATEGORY_TABLES.map { (name, tables) -> name to tables.any { categoryEnabled(it) } }

    fun setCategory(category: String, on: Boolean) {
        val tables = CATEGORY_TABLES[category] ?: return
        tables.forEach { setCategoryEnabled(it, on) }
    }

    /** Allowed command classes for one peer; empty = unrestricted. */
    fun peerPermissions(peerDeviceId: String): Set<String> =
        kvGet(PEER_PERM_PREFIX + peerDeviceId)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    fun setPeerPermissions(peerDeviceId: String, classes: Set<String>) {
        if (classes.isEmpty()) kvDelete(PEER_PERM_PREFIX + peerDeviceId)
        else kvPut(PEER_PERM_PREFIX + peerDeviceId, classes.sorted().joinToString(","))
    }

    private fun kvGet(key: String): String? {
        val db = database ?: return null
        return kotlinx.coroutines.runBlocking {
            runCatching { db.kvStoreDao().get(key) }.getOrNull()
        }
    }

    private fun kvPut(key: String, value: String) {
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching { db.kvStoreDao().put(com.newax.aegis.db.entity.KvStoreEntity(key, value)) }
        }
    }

    private fun kvDelete(key: String) {
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching { db.kvStoreDao().delete(key) }
        }
    }

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
            captureTrust(peer.deviceId, peer, tombstone = false)
            peer
        } catch (_: Exception) {
            null
        }
    }

    fun unpair(deviceId: String) {
        platformKeyStore().removePeer(deviceId)
        peerAddressFile(deviceId).delete()
        captureTrust(deviceId, null, tombstone = true)
    }

    // ── desktop-originated capture (item 8) + commands (item 6) ───────────────

    /**
     * Journal one of THIS device's own settings/goals into the mesh as a
     * syncable kv_store record (`syncable:<namespace>:<id>`) — the desktop
     * becomes a producer, not just a consumer. Applied on other devices via
     * their kv_store materializer (SyncPolicy.isSyncableKey gate). Gated by
     * the "Settings & preferences" category toggle (Fix E): when the user
     * turns the category off, the desktop stops journaling its own
     * preferences — mirror of SyncRuntime's per-category capture gate.
     */
    fun captureSettings(namespace: String, id: String, value: String) {
        if (namespace.isBlank() || id.isBlank()) return
        if (!categoryEnabled(TABLE_KV_STORE)) return
        journalRecord(TABLE_KV_STORE, SyncPolicy.syncKey(namespace, id), value.encodeToByteArray())
    }

    /**
     * Send a command to one paired peer (docs/SYNC_DESIGN.md §6): a LOG-kind
     * entry addressed to the peer's inbox. The target's CommandDispatcher
     * gates it by its per-peer allowlist + policy spine; it acks back.
     */
    fun sendCommand(peerDeviceId: String, commandClass: String, args: Map<String, String>) {
        if (commandClass !in COMMAND_CLASSES || peerDeviceId.isBlank()) return
        val ttl = System.currentTimeMillis() + DEFAULT_COMMAND_TTL_MS
        val payload = JSONObject().apply {
            put("class", commandClass)
            put("ttl", ttl.toString())
            args.forEach { (k, v) -> put(k, v) }
            // Per-entry Ed25519 signature (CommandSigning) — the target
            // verifies it against OUR paired public key before dispatching.
            put("sig", Hex.encode(CommandSigning.sign(platformCrypto(), identity().signPrivateKey, commandClass, ttl, args)))
        }.toString().encodeToByteArray()
        journalLog(TABLE_COMMANDS, COMMAND_TARGET_PREFIX + peerDeviceId, payload)
    }

    /** The paired peer's Ed25519 public key for [peerDeviceId], or null. */
    fun peerSignPublicKey(peerDeviceId: String): ByteArray? =
        platformKeyStore().pairedPeers().firstOrNull { it.deviceId == peerDeviceId }?.signPublicKey

    // ── Hierarchical agent memory — desktop producer (docs/MEMORY_DESIGN.md) ──
    // The desktop is a first-class producer of the three synced layers, mirror
    // of Android's AgentMemory: journal RECORD entries (LWW per id) + local
    // row. `agent_scratchpad` and `work_log` stay device-local on purpose.

    fun recordEpisode(agentId: String, category: String, summary: String, outcome: String, lesson: String = "") {
        if (summary.isBlank()) return
        val episodeId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching {
                db.agentMemoryDao().insertEpisode(
                    com.newax.aegis.db.entity.Episode(
                        episodeId = episodeId, agentId = agentId, category = category, summary = summary,
                        outcome = outcome, lesson = lesson, occurredAtMs = now
                    )
                )
            }
        }
        journalRecord(
            TABLE_EPISODES, episodeId,
            SyncPayload.encode(
                listOf(
                    "episodeId" to episodeId, "agentId" to agentId, "category" to category, "summary" to summary,
                    "outcome" to outcome, "lesson" to lesson, "occurredAtMs" to now.toString(), "contextRef" to ""
                )
            )
        )
    }

    fun submitKnowledge(category: String, title: String, content: String, confidence: Int = 80, source: String = "desktop") {
        if (category.isBlank() || title.isBlank() || content.isBlank()) return
        val entryId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching {
                db.agentMemoryDao().upsertLibrary(
                    com.newax.aegis.db.entity.LibraryEntry(
                        entryId = entryId, category = category, title = title, content = content,
                        confidence = confidence, source = source,
                        status = com.newax.aegis.db.entity.LibraryStatus.PENDING_APPROVAL, createdAtMs = now
                    )
                )
            }
        }
        journalRecord(
            TABLE_LIBRARY_ENTRIES, entryId,
            SyncPayload.encode(
                listOf(
                    "entryId" to entryId, "category" to category, "title" to title, "content" to content,
                    "confidence" to confidence.toString(), "source" to source,
                    "status" to com.newax.aegis.db.entity.LibraryStatus.PENDING_APPROVAL, "createdAtMs" to now.toString()
                )
            )
        )
    }

    fun approveKnowledge(entryId: String) {
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching { db.agentMemoryDao().setLibraryStatus(entryId, com.newax.aegis.db.entity.LibraryStatus.ACTIVE, System.currentTimeMillis()) }
        }
        journalRecord(
            TABLE_LIBRARY_ENTRIES, entryId,
            SyncPayload.encode(listOf("entryId" to entryId, "status" to com.newax.aegis.db.entity.LibraryStatus.ACTIVE))
        )
    }

    fun createHandoff(fromAgent: String, toAgent: String, task: String, summary: String, artifactJson: String = "{}") {
        if (fromAgent.isBlank() || toAgent.isBlank() || task.isBlank()) return
        val handoffId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching {
                db.agentMemoryDao().insertHandoff(
                    com.newax.aegis.db.entity.HandoffEntry(
                        handoffId = handoffId, fromAgent = fromAgent, toAgent = toAgent, task = task, summary = summary,
                        artifactJson = artifactJson, status = com.newax.aegis.db.entity.HandoffStatus.PENDING, createdAtMs = now
                    )
                )
            }
        }
        journalRecord(
            TABLE_HANDOFFS, handoffId,
            SyncPayload.encode(
                listOf(
                    "handoffId" to handoffId, "fromAgent" to fromAgent, "toAgent" to toAgent, "task" to task,
                    "summary" to summary, "artifactJson" to artifactJson,
                    "status" to com.newax.aegis.db.entity.HandoffStatus.PENDING, "createdAtMs" to now.toString()
                )
            )
        )
    }

    fun ackHandoff(handoffId: String) {
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching { db.agentMemoryDao().updateHandoffStatus(handoffId, com.newax.aegis.db.entity.HandoffStatus.ACKED) }
        }
        journalRecord(
            TABLE_HANDOFFS, handoffId,
            SyncPayload.encode(listOf("handoffId" to handoffId, "status" to com.newax.aegis.db.entity.HandoffStatus.ACKED))
        )
    }

    fun library(): List<com.newax.aegis.db.entity.LibraryEntry> {
        val db = database ?: return emptyList()
        return kotlinx.coroutines.runBlocking {
            runCatching { db.agentMemoryDao().activeLibrary() }.getOrDefault(emptyList())
        }
    }

    fun recentEpisodes(limit: Int = 30): List<com.newax.aegis.db.entity.Episode> {
        val db = database ?: return emptyList()
        return kotlinx.coroutines.runBlocking {
            runCatching { db.agentMemoryDao().recentEpisodes(limit) }.getOrDefault(emptyList())
        }
    }

    /** The target's acknowledgement back to the sender — surfaced in status. */
    fun sendCommandAck(toDeviceId: String, refOpId: String, result: String, reason: String = "") {
        if (toDeviceId.isBlank()) return
        val payload = JSONObject().apply {
            put("ref", refOpId)
            put("result", result)
            put("reason", reason)
        }.toString().encodeToByteArray()
        journalLog(TABLE_COMMANDS, COMMAND_ACK_PREFIX + toDeviceId, payload)
    }

    /** One row of the command history — sent commands and their acks (Fix B). */
    data class CommandHistoryEntry(
        val sent: Boolean,
        val peerDeviceId: String,
        val detail: String,
        val atMs: Long
    )

    /**
     * The most recent command activity on this device — `to:` entries we sent
     * and `ack:` entries targets sent back (newest first). Straight from the
     * journal, no separate history table.
     */
    fun commandHistory(limit: Int = 50): List<CommandHistoryEntry> {
        val db = database ?: return emptyList()
        return kotlinx.coroutines.runBlocking {
            runCatching { db.syncJournalDao().recentForTable(TABLE_COMMANDS, limit) }.getOrElse { emptyList() }
        }.mapNotNull { entry ->
            val key = entry.key
            val peer = when {
                key.startsWith(COMMAND_TARGET_PREFIX) -> key.removePrefix(COMMAND_TARGET_PREFIX)
                key.startsWith(COMMAND_ACK_PREFIX) -> key.removePrefix(COMMAND_ACK_PREFIX)
                else -> return@mapNotNull null
            }
            val payload = runCatching { JSONObject(entry.payload.decodeToString()) }.getOrNull() ?: return@mapNotNull null
            val detail = if (key.startsWith(COMMAND_TARGET_PREFIX)) {
                "→ ${payload.optString("class")}" +
                    payload.optString("name").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            } else {
                "ack ${payload.optString("result")}" +
                    payload.optString("reason").takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
            }
            CommandHistoryEntry(
                sent = key.startsWith(COMMAND_TARGET_PREFIX),
                peerDeviceId = peer,
                detail = detail,
                atMs = entry.createdAt
            )
        }
    }

    /** Journal one RECORD entry (LWW per key) — the desktop's capture path. */
    private fun journalRecord(table: String, key: String, payload: ByteArray, tombstone: Boolean = false) {
        val db = database ?: return
        val entry = SyncEntry.of(
            opId = java.util.UUID.randomUUID().toString(),
            deviceId = identity().identity.deviceId,
            hlc = nextHlc(),
            kind = SyncEntry.Kind.RECORD,
            table = table,
            key = key,
            payload = payload,
            tombstone = tombstone,
            createdAt = System.currentTimeMillis()
        )
        kotlinx.coroutines.runBlocking {
            runCatching { db.syncJournalDao().insert(entry.toEntity()) }
        }
    }

    /** Journal one LOG entry (append-only — the command inbox). */
    private fun journalLog(table: String, key: String, payload: ByteArray) {
        val db = database ?: return
        val entry = SyncEntry.of(
            opId = java.util.UUID.randomUUID().toString(),
            deviceId = identity().identity.deviceId,
            hlc = nextHlc(),
            kind = SyncEntry.Kind.LOG,
            table = table,
            key = key,
            payload = payload,
            tombstone = false,
            createdAt = System.currentTimeMillis()
        )
        kotlinx.coroutines.runBlocking {
            runCatching { db.syncJournalDao().insert(entry.toEntity()) }
        }
    }

    /** Journal this device's pairing decision (live record or revocation tombstone). */
    private fun captureTrust(peerDeviceId: String, peer: PairedPeer?, tombstone: Boolean) {
        val db = database ?: return
        val me = identity().identity.deviceId
        val fields = if (peer != null) {
            listOf(
                "deviceId" to peer.deviceId,
                "displayName" to peer.displayName,
                "signPublicKey" to Hex.encode(peer.signPublicKey),
                "ecdhPublicKey" to Hex.encode(peer.ecdhPublicKey),
                "pairedAtMs" to peer.pairedAtMs.toString()
            )
        } else {
            listOf("deviceId" to peerDeviceId)
        }
        val entry = SyncEntry.of(
            opId = java.util.UUID.randomUUID().toString(),
            deviceId = me,
            hlc = nextHlc(),
            kind = SyncEntry.Kind.RECORD,
            table = TABLE_PEER_TRUST,
            key = me + TRUST_SEP + peerDeviceId,
            payload = SyncPayload.encode(fields),
            tombstone = tombstone,
            createdAt = System.currentTimeMillis()
        )
        kotlinx.coroutines.runBlocking {
            runCatching { db.syncJournalDao().insert(entry.toEntity()) }
        }
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
        // Commands are LOG-kind (append-only, targeted) — every entry is
        // processed, opId dedup happens at append. The desktop has no command
        // dispatcher yet (its action executor lands with Track M), so commands
        // targeting THIS device are refused with an explicit ack — the sender
        // never waits silently (item 6). Acks TO this device surface in status.
        if (entry.kind == SyncEntry.Kind.LOG && entry.table == TABLE_COMMANDS) {
            val key = entry.key
            val myId = identity().identity.deviceId
            when {
                key.startsWith(COMMAND_ACK_PREFIX) && key.removePrefix(COMMAND_ACK_PREFIX) == myId -> {
                    val p = runCatching { JSONObject(entry.payload.decodeToString()) }.getOrNull() ?: return
                    lastStatus = "command ${p.optString("ref").take(8)} → ${p.optString("result")}" +
                        (p.optString("reason").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                }
                key.startsWith(COMMAND_TARGET_PREFIX) && key.removePrefix(COMMAND_TARGET_PREFIX) == myId -> {
                    // Fix C — the desktop now dispatches through its own
                    // CommandDispatcher (registered by the desktop frontend body);
                    // without one, refuse explicitly so the sender never waits
                    // silently.
                    val dispatcher = commandDispatcher
                    if (dispatcher != null) {
                        runCatching { dispatcher(entry) }
                    } else {
                        sendCommandAck(entry.deviceId, entry.opId, "refused", "desktop-dispatch-not-wired")
                    }
                }
            }
            return
        }
        if (entry.kind != SyncEntry.Kind.RECORD) return
        // Per-category toggle — disabled categories are not applied (mirror of
        // SyncRuntime; peer_trust always flows).
        if (entry.table != TABLE_PEER_TRUST && !categoryEnabled(entry.table)) return
        runCatching {
            when (entry.table) {
                TABLE_MEMORY_PROFILE -> if (!entry.tombstone) materializeMemory(entry)
                // Record tables (Slice 1): full-state LWW per natural key.
                "entities" -> materializeEntity(entry)
                "predicates" -> materializePredicate(entry)
                "entity_aliases" -> materializeAlias(entry)
                "persons" -> materializePerson(entry)
                "person_facts" -> materializePersonFact(entry)
                // Fabric tables (item 1): graph edges, app usage, triggers, settings.
                TABLE_EDGES -> materializeEdge(entry)
                TABLE_APP_RECORDS -> materializeAppRecord(entry)
                TABLE_APP_CAPABILITY_LINKS -> materializeAppCapabilityLink(entry)
                TABLE_TRIGGER_RULES -> materializeTriggerRule(entry)
                TABLE_KV_STORE -> materializeKv(entry)
                TABLE_EPISODES -> materializeEpisode(entry)
                TABLE_HANDOFFS -> materializeHandoff(entry)
                TABLE_LIBRARY_ENTRIES -> materializeLibrary(entry)
                TABLE_PEER_TRUST -> materializeTrust(entry)
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

    /** Resolve (or create) an entity by canonical name — the edge materializer's ref resolver. */
    private fun entityIdFor(name: String): Long? {
        val db = database ?: return null
        return kotlinx.coroutines.runBlocking {
            runCatching {
                val dao = db.graphDao()
                dao.findByName(name)?.id
                    ?: dao.insertEntity(GraphEntity(type = 0, canonicalName = name, createdAt = System.currentTimeMillis()))
            }.getOrNull()
        }
    }

    /** Incoming graph edge (item 1) — names resolve to local ids; dupes deduped. */
    private fun materializeEdge(entry: SyncEntry) {
        if (entry.tombstone || locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val subject = fields["subject"]?.takeIf { it.isNotBlank() } ?: return
        val predicate = fields["predicate"]?.takeIf { it.isNotBlank() } ?: return
        val objectName = fields["object"]?.takeIf { it.isNotBlank() }
        val objectValue = fields["objectValue"]?.takeIf { it.isNotBlank() }
        if (objectName == null && objectValue == null) return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching {
                val dao = db.graphDao()
                val subjectId = entityIdFor(subject)
                val predicateId = dao.predicateByName(predicate)?.id
                    ?: dao.insertPredicate(GraphPredicate(name = predicate))
                val objectId = objectName?.let { entityIdFor(it) }
                if (subjectId == null || predicateId == 0L) return@runCatching
                val dup = dao.currentEdgesBySubjectPredicate(subjectId, predicateId).any {
                    it.objectId == objectId && it.objectValue == objectValue && it.validUntil == null
                }
                if (dup) return@runCatching
                dao.insertEdge(
                    GraphEdge(
                        subjectId = subjectId,
                        predicateId = predicateId,
                        objectId = objectId,
                        objectValue = objectValue,
                        confidence = fields["confidence"]?.toIntOrNull() ?: 80,
                        importance = fields["importance"]?.toIntOrNull() ?: 50,
                        createdAt = fields["createdAt"]?.toLongOrNull() ?: entry.createdAt,
                        validFrom = fields["validFrom"]?.toLongOrNull(),
                        validUntil = fields["validUntil"]?.toLongOrNull()
                    )
                )
            }
        }
    }

    /** Incoming app record (item 1) — LWW per package name. */
    private fun materializeAppRecord(entry: SyncEntry) {
        if (entry.tombstone || locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val pkg = fields["packageName"]?.takeIf { it.isNotBlank() } ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching {
                db.appRegistryDao().upsertRecord(
                    AppRecord(
                        packageName = pkg,
                        label = fields["label"] ?: pkg,
                        version = fields["version"] ?: "",
                        category = fields["category"] ?: "",
                        launchActivity = fields["launchActivity"],
                        lastScanMs = fields["lastScanMs"]?.toLongOrNull() ?: entry.createdAt
                    )
                )
            }
        }
    }

    /** Incoming app capability link (Fix H) — LWW per (packageName, capability). */
    private fun materializeAppCapabilityLink(entry: SyncEntry) {
        if (entry.tombstone || locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val pkg = fields["packageName"]?.takeIf { it.isNotBlank() } ?: return
        val cap = fields["capability"]?.takeIf { it.isNotBlank() } ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching {
                db.appRegistryDao().upsertLink(
                    com.newax.aegis.db.entity.AppCapabilityLink(
                        packageName = pkg,
                        capability = cap,
                        intentAction = fields["intentAction"],
                        deepLinkPattern = fields["deepLinkPattern"],
                        mimeTypes = fields["mimeTypes"],
                        confidence = fields["confidence"]?.toIntOrNull() ?: 80
                    )
                )
            }
        }
    }

    /**
     * Incoming episode (hierarchical memory, schema v14) — LWW per episodeId;
     * tombstones delete. Append-only facts → insert-if-absent.
     */
    private fun materializeEpisode(entry: SyncEntry) {
        if (locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val episodeId = fields["episodeId"]?.takeIf { it.isNotBlank() } ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching {
                val dao = db.agentMemoryDao()
                if (entry.tombstone) {
                    dao.deleteEpisode(episodeId)
                } else {
                    dao.upsertEpisode(
                        com.newax.aegis.db.entity.Episode(
                            episodeId = episodeId,
                            agentId = fields["agentId"] ?: "",
                            category = fields["category"] ?: "",
                            summary = fields["summary"] ?: "",
                            outcome = fields["outcome"] ?: com.newax.aegis.db.entity.EpisodeOutcome.OBSERVATION,
                            lesson = fields["lesson"] ?: "",
                            occurredAtMs = fields["occurredAtMs"]?.toLongOrNull() ?: entry.createdAt,
                            contextRef = fields["contextRef"] ?: ""
                        )
                    )
                }
            }
        }
    }

    /** Incoming handoff (L3 shared write) — LWW per handoffId; status merges. */
    private fun materializeHandoff(entry: SyncEntry) {
        if (locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val handoffId = fields["handoffId"]?.takeIf { it.isNotBlank() } ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching {
                val dao = db.agentMemoryDao()
                if (entry.tombstone) {
                    dao.deleteHandoff(handoffId)
                    return@runCatching
                }
                val existing = dao.handoffById(handoffId)
                val status = fields["status"]?.takeIf { it.isNotBlank() }
                if (existing != null && status != null) {
                    dao.updateHandoffStatus(handoffId, status)
                } else if (existing == null) {
                    dao.upsertHandoff(
                        com.newax.aegis.db.entity.HandoffEntry(
                            handoffId = handoffId,
                            fromAgent = fields["fromAgent"] ?: "",
                            toAgent = fields["toAgent"] ?: "",
                            task = fields["task"] ?: "",
                            summary = fields["summary"] ?: "",
                            artifactJson = fields["artifactJson"] ?: "{}",
                            status = status ?: com.newax.aegis.db.entity.HandoffStatus.PENDING,
                            refId = fields["refId"] ?: "",
                            createdAtMs = fields["createdAtMs"]?.toLongOrNull() ?: entry.createdAt
                        )
                    )
                }
            }
        }
    }

    /** Incoming library entry (L1 gated library) — LWW per entryId; status merges. */
    private fun materializeLibrary(entry: SyncEntry) {
        if (locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val entryId = fields["entryId"]?.takeIf { it.isNotBlank() } ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching {
                val dao = db.agentMemoryDao()
                if (entry.tombstone) {
                    dao.deleteLibrary(entryId)
                    return@runCatching
                }
                val existing = dao.libraryById(entryId)
                val status = fields["status"]?.takeIf { it.isNotBlank() }
                if (existing != null && status != null) {
                    // Decay propagation: an incoming confidence rides with the status.
                    fields["confidence"]?.toIntOrNull()?.let { confidence ->
                        if (confidence != existing.confidence) dao.updateLibraryConfidence(entryId, confidence)
                    }
                    dao.setLibraryStatus(entryId, status, System.currentTimeMillis())
                } else if (existing == null) {
                    dao.upsertLibrary(
                        com.newax.aegis.db.entity.LibraryEntry(
                            entryId = entryId,
                            category = fields["category"] ?: "",
                            title = fields["title"] ?: "",
                            content = fields["content"] ?: "",
                            confidence = fields["confidence"]?.toIntOrNull() ?: 80,
                            source = fields["source"] ?: "",
                            status = status ?: com.newax.aegis.db.entity.LibraryStatus.PENDING_APPROVAL,
                            createdAtMs = fields["createdAtMs"]?.toLongOrNull() ?: entry.createdAt
                        )
                    )
                }
            }
        }
    }

    /** Incoming trigger rule (item 1) — LWW per label; tombstones delete. */
    private fun materializeTriggerRule(entry: SyncEntry) {
        if (locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val label = fields["label"]?.takeIf { it.isNotBlank() } ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            runCatching {
                val dao = db.triggerDao()
                if (entry.tombstone) {
                    dao.allRules().firstOrNull { it.label == label }?.let { dao.deleteById(it.id) }
                    return@runCatching
                }
                if (dao.allRules().any { it.label == label }) return@runCatching
                dao.insert(
                    TriggerRule(
                        label = label,
                        conditionType = fields["conditionType"] ?: "",
                        conditionParams = fields["conditionParams"] ?: "{}",
                        actionType = fields["actionType"] ?: "",
                        actionParams = fields["actionParams"] ?: "{}",
                        enabled = fields["enabled"] != "false",
                        debounceMs = fields["debounceMs"]?.toLongOrNull() ?: 30_000L,
                        createdMs = fields["createdMs"]?.toLongOrNull() ?: entry.createdAt
                    )
                )
            }
        }
    }

    /** Incoming syncable kv_store key (item 8) — mirror of SyncRuntime.materializeKv. */
    private fun materializeKv(entry: SyncEntry) {
        val localKey = SyncPolicy.localKey(entry.key) ?: return
        val db = database ?: return
        kotlinx.coroutines.runBlocking {
            if (entry.tombstone) {
                runCatching { db.kvStoreDao().delete(localKey) }
            } else {
                runCatching {
                    db.kvStoreDao().put(com.newax.aegis.db.entity.KvStoreEntity(localKey, entry.payload.decodeToString()))
                }
            }
        }
    }

    /**
     * Re-apply this device's own trust records — mirror of
     * SyncRuntime.materializeTrust (tombstone re-removes, live record
     * restores; the LWW guard keeps the newest local decision).
     */
    private fun materializeTrust(entry: SyncEntry) {
        if (locallyNewer(entry)) return
        val parts = entry.key.split(TRUST_SEP)
        if (parts.size != 2 || parts[0] != identity().identity.deviceId) return
        val peerId = parts[1]
        if (entry.tombstone) {
            platformKeyStore().removePeer(peerId)
            peerAddressFile(peerId).delete()
            return
        }
        val fields = SyncPayload.decode(entry.payload)
        val sign = fields["signPublicKey"]?.let { Hex.decode(it) } ?: return
        val ecdh = fields["ecdhPublicKey"]?.let { Hex.decode(it) } ?: return
        platformKeyStore().savePeer(
            PairedPeer(
                deviceId = peerId,
                displayName = fields["displayName"] ?: peerId,
                signPublicKey = sign,
                ecdhPublicKey = ecdh,
                pairedAtMs = fields["pairedAtMs"]?.toLongOrNull() ?: entry.createdAt
            )
        )
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
