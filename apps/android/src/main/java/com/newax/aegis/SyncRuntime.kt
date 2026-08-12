package com.newax.aegis

import android.content.Context
import android.os.Build
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.db.entity.AppRecord
import com.newax.aegis.db.entity.EntityAlias
import com.newax.aegis.db.entity.Episode
import com.newax.aegis.db.entity.EpisodeOutcome
import com.newax.aegis.db.entity.GraphEdge
import com.newax.aegis.db.entity.GraphEntity
import com.newax.aegis.db.entity.GraphPredicate
import com.newax.aegis.db.entity.HandoffEntry
import com.newax.aegis.db.entity.HandoffStatus
import com.newax.aegis.db.entity.KvStoreEntity
import com.newax.aegis.db.entity.LibraryEntry
import com.newax.aegis.db.entity.LibraryStatus
import com.newax.aegis.db.entity.PersonEntity
import com.newax.aegis.db.entity.PersonFactEntity
import com.newax.aegis.db.entity.TriggerRule
import com.newax.aegis.db.sync.SyncPayload
import com.newax.aegis.db.sync.toEntity
import com.newax.aegis.memory.EncryptedMemory
import com.newax.aegis.sync.CommandSigning
import com.newax.aegis.sync.Crypto
import com.newax.aegis.sync.Hex
import com.newax.aegis.sync.Hlc
import com.newax.aegis.sync.Identity
import com.newax.aegis.sync.KeyStore
import com.newax.aegis.sync.PairedPeer
import com.newax.aegis.sync.Pairing
import com.newax.aegis.sync.Pairing.PairingRequest
import com.newax.aegis.sync.PeerEndpoint
import com.newax.aegis.sync.StoredIdentity
import com.newax.aegis.sync.SyncEntry
import com.newax.aegis.sync.SyncPolicy
import com.newax.aegis.sync.platformCrypto
import com.newax.aegis.sync.platformKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The Android sync coordinator — the app-side wiring slice (docs/SYNC_DESIGN.md
 * §4, §9) around the engine's [JournalStore]/transport seams:
 *
 *  - device identity (TEE-wrapped via [platformKeyStore], generated once),
 *  - a process-local HLC that stamps every captured mutation,
 *  - capture: user-facing memory changes → journal entries (`memory_profile`),
 *  - materialize: incoming journal entries → local state (memory prefs,
 *    `syncable:` kv_store keys),
 *  - pairing: text-code exchange + human-verified SAS (no QR camera needed),
 *  - automation settings persisted in kv_store under non-syncable keys
 *    (`sync:...` — they never leave the device, per SyncPolicy).
 *
 * Journaling is best-effort by design (R9): every capture/materialize call is
 * guarded so sync can never break the write path it observes.
 */
object SyncRuntime {

    /** Journal table name for the encrypted memory profile (per-category RECORD). */
    const val TABLE_MEMORY_PROFILE = "memory_profile"

    /**
     * Journal table for pairing/revocation records (docs/SYNC_DESIGN.md §3):
     * each device owns its own rows, keyed `$myDeviceId\u0001$peerDeviceId`
     * (namespaced so different revokers' rows never LWW-collide). Pairing
     * journals a live RECORD; unpair journals a tombstone — the revocation
     * propagates through the mesh journal and is re-applied after a reinstall.
     */
    const val TABLE_PEER_TRUST = "peer_trust"
    private const val TRUST_SEP = "\u0001"

    /**
     * Journal table names for the remaining syncable fabric (item 1 — capture
     * coverage): graph edges, app usage records, and user trigger rules. The
     * names match the schema-v13 table names, so the same journal namespace
     * works on every platform. `commands` is the targeted command inbox
     * (LOG-kind, append-only — item 6).
     */
    const val TABLE_EDGES = "edges"
    const val TABLE_APP_RECORDS = "app_records"
    const val TABLE_APP_CAPABILITY_LINKS = "app_capability_links"
    const val TABLE_TRIGGER_RULES = "trigger_rules"
    const val TABLE_COMMANDS = "commands"

    /**
     * The three synced layers of the hierarchical agent memory (schema v14,
     * docs/MEMORY_DESIGN.md): episodes (collective learning), handoffs (shared
     * write), library_entries (the gated Global Library). Journal keys are the
     * entry ids; LWW per key like every other RECORD table. `agent_scratchpad`
     * and `work_log` deliberately have no constants here — they never sync.
     */
    const val TABLE_EPISODES = "episodes"
    const val TABLE_HANDOFFS = "handoffs"
    const val TABLE_LIBRARY_ENTRIES = "library_entries"
    private const val EDGE_SEP = "\u0001"
    private const val LINK_SEP = "\u0001"
    private const val COMMAND_TARGET_PREFIX = "to:"
    private const val COMMAND_ACK_PREFIX = "ack:"

    /** Commands expire after this long unprocessed (design §6 ttl). */
    private const val DEFAULT_COMMAND_TTL_MS = 24 * 60 * 60 * 1000L

    /** Local-only kv_store keys (NOT under `syncable:` — they never sync). */
    private const val KEY_ENABLED = "sync:enabled"
    private const val KEY_STATUS = "sync:status"
    private const val KEY_RELAY = "sync:relay"
    private const val ADDR_PREFIX = "sync:addr:"
    private const val CAT_PREFIX = "sync:cat:"
    private const val PEER_PERM_PREFIX = "sync:peerperm:"

    /**
     * The per-category sync toggles (design §5, S5 settings surface): the four
     * user-facing categories map to the journal table names that are captured
     * today. A disabled category is gated at capture (nothing new journaled)
     * AND at materialize (nothing applied) — the peer's older entries stay in
     * the journal but stop touching local state. `peer_trust` is deliberately
     * not in any category: revocation must always flow.
     */
    val CATEGORY_TABLES: Map<String, List<String>> = linkedMapOf(
        "Memory profile" to listOf(TABLE_MEMORY_PROFILE),
        "Knowledge graph" to listOf("entities", "predicates", "entity_aliases"),
        "People" to listOf("persons", "person_facts"),
        "Settings & preferences" to listOf("kv_store")
    )

    /**
     * Command classes a peer may send to this device (design §6). The set is
     * stored per peer (`sync:peerperm:<deviceId>`, local, never synced); an
     * EMPTY set means unrestricted — the default. Enforcement lands with S6
     * command dispatch, which gates on this; the settings surface ships now
     * (design sequencing: S5 settings before S6 commands).
     */
    val COMMAND_CLASSES = listOf(
        "send_email", "open_app", "open_file", "browse_files",
        "run_goal", "system_query", "run_shell"
    )

    fun categoryEnabled(table: String): Boolean = kvGet(CAT_PREFIX + table) != "0"

    fun setCategoryEnabled(table: String, on: Boolean) {
        if (on) kvDelete(CAT_PREFIX + table) else kvPut(CAT_PREFIX + table, "0")
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val hlcLock = Any()
    private var clock = Hlc(System.currentTimeMillis(), 0)

    @Volatile
    private var memory: EncryptedMemory? = null

    private val keyStoreHolder: KeyStore by lazy { platformKeyStore() }
    private val cryptoHolder: Crypto by lazy { platformCrypto() }
    private val identityHolder: StoredIdentity by lazy {
        keyStoreHolder.loadIdentity()
            ?: Identity.generate(cryptoHolder, "Android " + Build.MODEL)
                .also { keyStoreHolder.saveIdentity(it) }
    }

    /** Call once from Application.onCreate — primes identity + memory target. */
    fun init(context: Context) {
        memory = EncryptedMemory(context.applicationContext)
        identityHolder
    }

    fun identity(): StoredIdentity = identityHolder
    fun keyStore(): KeyStore = keyStoreHolder
    fun crypto(): Crypto = cryptoHolder
    fun deviceId(): String = identityHolder.identity.deviceId
    fun displayName(): String = identityHolder.identity.displayName
    fun pairingCode(): String = Pairing.createRequest(cryptoHolder, identityHolder.identity).encode()

    // ── Automation settings (local kv_store, never synced) ──────────────────

    /** Auto-sync defaults ON — a no-op until at least one peer is paired. */
    fun enabled(): Boolean = kvGet(KEY_ENABLED) != "0"

    fun setEnabled(on: Boolean) {
        kvPut(KEY_ENABLED, if (on) "1" else "0")
    }

    fun status(): String = kvGet(KEY_STATUS) ?: "Never synced"

    fun recordStatus(text: String) {
        kvPut(KEY_STATUS, text)
    }

    // ── Peers ───────────────────────────────────────────────────────────────

    fun peers(): List<PairedPeer> = keyStoreHolder.pairedPeers()

    fun unpair(deviceId: String) {
        keyStoreHolder.removePeer(deviceId)
        kvDelete(ADDR_PREFIX + deviceId)
        // Revocation record — propagates through the mesh journal; other
        // devices re-apply it (and the relay's REG-reset drops the grant).
        captureRecord(
            TABLE_PEER_TRUST, trustKey(deviceId),
            listOf("deviceId" to deviceId), tombstone = true
        )
    }

    private fun trustKey(peerDeviceId: String): String = deviceId() + TRUST_SEP + peerDeviceId

    /**
     * The relay server URL (`ws://host:port` or `wss://...`) for WAN sync
     * (docs/SYNC_DESIGN.md §10). Empty/blank = relay off — LAN only. Local
     * config like the auto toggle, never synced.
     */
    fun relayUrl(): String = kvGet(KEY_RELAY)?.trim().orEmpty()

    fun setRelayUrl(url: String) {
        if (url.isBlank()) kvDelete(KEY_RELAY) else kvPut(KEY_RELAY, url.trim())
    }

    /** Manually-entered `host:port` for a paired peer (mDNS-free bootstrap). */
    fun peerAddress(deviceId: String): String? = kvGet(ADDR_PREFIX + deviceId)

    fun setPeerAddress(deviceId: String, address: String) {
        if (address.isBlank()) kvDelete(ADDR_PREFIX + deviceId) else kvPut(ADDR_PREFIX + deviceId, address)
    }

    /** Endpoints for peers that have a stored `host:port` — direct connect. */
    fun manualEndpoints(): List<PeerEndpoint> {
        val out = mutableListOf<PeerEndpoint>()
        val rows = runBlocking {
            runCatching { NewaxDatabase.get.kvStoreDao().getWithPrefix(ADDR_PREFIX) }.getOrNull().orEmpty()
        }
        for (row in rows) {
            val peerId = row.key.removePrefix(ADDR_PREFIX)
            val peer = keyStoreHolder.pairedPeers().firstOrNull { it.deviceId == peerId } ?: continue
            val parts = row.value.split(":")
            val host = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: continue
            val port = parts.getOrNull(1)?.toIntOrNull() ?: continue
            out.add(PeerEndpoint(peerId, peer.displayName, host, port))
        }
        return out
    }

    // ── Pairing (text code + SAS, no camera) ────────────────────────────────

    /**
     * The 6-digit SAS both devices must show — null on a malformed code.
     *
     * The SAS is a function of (initiatorSignKey, responderSignKey, initiator
     * nonce), so both devices must agree on WHO the initiator is or they get
     * different numbers. Canonical rule: the device whose pairing code has the
     * lexicographically smaller sign-key hex is the initiator — both sides can
     * determine that independently, so they converge on one SAS.
     */
    fun sasFor(myCode: String, theirCode: String): String? {
        val mine = PairingRequest.decode(myCode.trim()) ?: return null
        val theirs = PairingRequest.decode(theirCode.trim()) ?: return null
        val mySignHex = Hex.encode(mine.signPublicKey)
        val theirSignHex = Hex.encode(theirs.signPublicKey)
        return if (mySignHex <= theirSignHex) {
            Pairing.sas(mine.signPublicKey, theirs.signPublicKey, mine.nonce)
        } else {
            Pairing.sas(theirs.signPublicKey, mine.signPublicKey, theirs.nonce)
        }
    }

    /**
     * Complete pairing from the peer's code: store them as a paired peer
     * (mutual — they must pair with us the same way). Null on malformed code
     * or self-pair.
     */
    fun pairWith(pasted: String): PairedPeer? {
        val request = PairingRequest.decode(pasted.trim()) ?: return null
        return try {
            val peer = Pairing.confirmResponder(
                request = request,
                myDeviceId = identityHolder.identity.deviceId,
                mySignPublicKey = identityHolder.identity.signPublicKey,
                nowMs = System.currentTimeMillis()
            )
            keyStoreHolder.savePeer(peer)
            // Pairing record — the durable counterpart of the revocation
            // tombstone (restores the peer after a reinstall, cancels older
            // tombstones via LWW).
            captureRecord(
                TABLE_PEER_TRUST, trustKey(peer.deviceId),
                listOf(
                    "deviceId" to peer.deviceId,
                    "displayName" to peer.displayName,
                    "signPublicKey" to Hex.encode(peer.signPublicKey),
                    "ecdhPublicKey" to Hex.encode(peer.ecdhPublicKey),
                    "pairedAtMs" to peer.pairedAtMs.toString()
                )
            )
            peer
        } catch (_: Exception) {
            null
        }
    }

    // ── Capture (local change → journal) ────────────────────────────────────

    /** Journal the full state of one memory-profile category (LWW per category). */
    fun captureMemoryProfile(category: String, facts: List<String>) {
        val payload = JSONArray().apply { facts.forEach { put(it) } }.toString().encodeToByteArray()
        capture(SyncEntry.Kind.RECORD, TABLE_MEMORY_PROFILE, category, payload)
    }

    /**
     * Journal the full state of one record (LWW per key) for the syncable DB
     * tables — the payload is [SyncPayload]-encoded ordered fields, the key is
     * the record's natural key (canonical entity name, predicate name, alias,
     * person name, personName + fact). Best-effort like [capture]: never
     * breaks the write path it observes.
     */
    fun captureRecord(table: String, key: String, fields: List<Pair<String, String>>, tombstone: Boolean = false) {
        capture(SyncEntry.Kind.RECORD, table, key, SyncPayload.encode(fields), tombstone)
    }

    /**
     * Journal one graph edge by its cross-device natural key (names, not local
     * ids — entity ids differ per device). [objectName] is the target entity's
     * canonical name, or null when the edge holds an inline [objectValue].
     */
    /**
     * Journal one app capability link (Fix H) — LWW per (packageName,
     * capability) composite key, same namespace on both platforms.
     */
    fun captureCapabilityLink(link: com.newax.aegis.db.entity.AppCapabilityLink) {
        captureRecord(
            TABLE_APP_CAPABILITY_LINKS,
            link.packageName + LINK_SEP + link.capability,
            listOf(
                "packageName" to link.packageName,
                "capability" to link.capability,
                "intentAction" to (link.intentAction ?: ""),
                "deepLinkPattern" to (link.deepLinkPattern ?: ""),
                "mimeTypes" to (link.mimeTypes ?: ""),
                "confidence" to link.confidence.toString()
            )
        )
    }

    fun captureEdge(
        subjectName: String,
        predicateName: String,
        objectName: String?,
        objectValue: String?,
        confidence: Int,
        importance: Int,
        createdAt: Long,
        validFrom: Long?,
        validUntil: Long?
    ) {
        val obj = objectName ?: objectValue.orEmpty()
        if (subjectName.isBlank() || predicateName.isBlank() || obj.isBlank()) return
        captureRecord(
            TABLE_EDGES,
            listOf(subjectName, predicateName, obj).joinToString(EDGE_SEP),
            listOf(
                "subject" to subjectName,
                "predicate" to predicateName,
                "object" to (objectName ?: ""),
                "objectValue" to (objectValue ?: ""),
                "confidence" to confidence.toString(),
                "importance" to importance.toString(),
                "createdAt" to createdAt.toString(),
                "validFrom" to (validFrom?.toString() ?: ""),
                "validUntil" to (validUntil?.toString() ?: "")
            )
        )
    }

    // ── Commands (item 6 — targeted, allowlist-gated, AGENT-origin) ──────────

    /**
     * Send a command to one paired peer (docs/SYNC_DESIGN.md §6): a LOG-kind
     * journal entry addressed to the peer's inbox (`to:<peerDeviceId>`). Every
     * device that carries the journal relays it store-and-forward; ONLY the
     * target dispatches it (CommandDispatcher), gated by the target's per-peer
     * allowlist and its policy spine as AGENT origin. The peer acks back via
     * [sendCommandAck]. Authoring surfaces: the Sync screen's per-peer "Send
     * command" dialog and the desktop CLI (`sync send`).
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
            put("sig", Hex.encode(CommandSigning.sign(cryptoHolder, identityHolder.signPrivateKey, commandClass, ttl, args)))
        }.toString().encodeToByteArray()
        capture(SyncEntry.Kind.LOG, TABLE_COMMANDS, COMMAND_TARGET_PREFIX + peerDeviceId, payload)
    }

    /** The paired peer's Ed25519 public key for [peerDeviceId], or null. */
    fun peerSignPublicKey(peerDeviceId: String): ByteArray? =
        keyStoreHolder.pairedPeers().firstOrNull { it.deviceId == peerDeviceId }?.signPublicKey

    /** The target's acknowledgement (executed/refused/expired) back to the sender. */
    fun sendCommandAck(toDeviceId: String, refOpId: String, result: String, reason: String = "") {
        if (toDeviceId.isBlank()) return
        val payload = JSONObject().apply {
            put("ref", refOpId)
            put("result", result)
            put("reason", reason)
        }.toString().encodeToByteArray()
        capture(SyncEntry.Kind.LOG, TABLE_COMMANDS, COMMAND_ACK_PREFIX + toDeviceId, payload)
    }

    /** One row of the command history — sent commands and their acks (Fix B). */
    data class CommandHistoryEntry(
        val sent: Boolean,
        val peerDeviceId: String,
        val detail: String,
        val atMs: Long
    )

    /**
     * The most recent command activity on this device: `to:` entries we sent
     * and `ack:` entries the targets sent back (newest first). Reads straight
     * from the journal — no separate history table.
     */
    fun commandHistory(limit: Int = 50): List<CommandHistoryEntry> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking {
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

    fun capture(
        kind: SyncEntry.Kind,
        table: String,
        key: String,
        payload: ByteArray,
        tombstone: Boolean = false
    ) {
        if (!enabled()) return
        // Per-category toggle — a disabled category journals nothing new.
        // peer_trust is not in any category, so revocation always flows.
        if (table != TABLE_PEER_TRUST && !categoryEnabled(table)) return
        val entry = SyncEntry.of(
            opId = UUID.randomUUID().toString(),
            deviceId = deviceId(),
            hlc = nextHlc(),
            kind = kind,
            table = table,
            key = key,
            payload = payload,
            tombstone = tombstone,
            createdAt = System.currentTimeMillis()
        )
        scope.launch {
            try {
                NewaxDatabase.get.syncJournalDao().insert(entry.toEntity())
            } catch (_: Exception) {
                // Best-effort journaling — never break the caller's write.
            }
        }
    }

    // ── Materialize (incoming journal → local state) ────────────────────────

    /**
     * Apply received journal entries. RECORD entries carry the full state of
     * their key; applying in (hlc, deviceId) order converges to the LWW winner
     * (JournalMerge semantics).
     */
    fun materialize(entries: List<SyncEntry>) {
        for (entry in entries) {
            // Commands are LOG-kind (append-only, targeted) — every entry is
            // processed, opId dedup happens at append. Only MY inbox dispatches.
            if (entry.kind == SyncEntry.Kind.LOG && entry.table == TABLE_COMMANDS) {
                try {
                    CommandDispatcher.onIncoming(entry)
                } catch (_: Exception) {
                    // A malformed command must not kill the rest of the round.
                }
                continue
            }
            if (entry.kind != SyncEntry.Kind.RECORD) continue
            // Per-category toggle — disabled categories are not applied either
            // (defense in depth; older entries stay in the journal, harmless).
            if (entry.table != TABLE_PEER_TRUST && !categoryEnabled(entry.table)) continue
            try {
                when (entry.table) {
                    TABLE_MEMORY_PROFILE -> if (!entry.tombstone) materializeMemoryProfile(entry)
                    "kv_store" -> if (SyncPolicy.isSyncableKey(entry.key)) materializeKv(entry)
                    // Record tables (Slice 1): full-state LWW per natural key.
                    "entities" -> materializeEntity(entry)
                    "predicates" -> materializePredicate(entry)
                    "entity_aliases" -> materializeAlias(entry)
                    "persons" -> materializePerson(entry)
                    "person_facts" -> materializePersonFact(entry)
                    // Fabric tables (item 1): graph edges, app usage, triggers.
                    TABLE_EDGES -> materializeEdge(entry)
                    TABLE_APP_RECORDS -> materializeAppRecord(entry)
                    TABLE_APP_CAPABILITY_LINKS -> materializeAppCapabilityLink(entry)
                    TABLE_TRIGGER_RULES -> materializeTriggerRule(entry)
                    // Hierarchical agent memory (schema v14): the synced layers.
                    TABLE_EPISODES -> materializeEpisode(entry)
                    TABLE_HANDOFFS -> materializeHandoff(entry)
                    TABLE_LIBRARY_ENTRIES -> materializeLibrary(entry)
                    // Pairing/revocation records (Slice 5).
                    TABLE_PEER_TRUST -> materializeTrust(entry)
                    else -> Unit // tables not yet captured/materialized
                }
            } catch (_: Exception) {
                // A malformed payload must not kill the rest of the round.
            }
        }
    }

    /**
     * LWW guard: skip an incoming RECORD when the local journal already holds
     * a strictly newer entry for the same (table, key). Ordering is the
     * engine's journal order (hlcWall, hlcCounter, deviceId). The incoming
     * entry is in the journal by the time materialize runs (the anti-entropy
     * round appends before applying), so "equal" means the entry itself.
     */
    private fun locallyNewer(entry: SyncEntry): Boolean = runBlocking {
        runCatching {
            val latest = NewaxDatabase.get.syncJournalDao().latestFor(entry.table, entry.key)
                ?: return@runCatching false
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
        runBlocking {
            if (NewaxDatabase.get.graphDao().findByName(name) != null) return@runBlocking
            NewaxDatabase.get.graphDao().insertEntity(
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
        runBlocking {
            if (NewaxDatabase.get.graphDao().predicateByName(name) != null) return@runBlocking
            NewaxDatabase.get.graphDao().insertPredicate(GraphPredicate(name = name))
        }
    }

    private fun materializeAlias(entry: SyncEntry) {
        if (entry.tombstone || locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val alias = fields["alias"]?.takeIf { it.isNotBlank() } ?: return
        val entityName = fields["entityName"]?.takeIf { it.isNotBlank() } ?: return
        runBlocking {
            val dao = NewaxDatabase.get.graphDao()
            if (dao.findEntityByAlias(alias) != null) return@runBlocking
            val entityId = dao.findByName(entityName)?.id ?: return@runBlocking
            dao.insertAlias(EntityAlias(entityId = entityId, alias = alias))
        }
    }

    private fun materializePerson(entry: SyncEntry) {
        if (entry.tombstone || locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return
        runBlocking {
            val dao = NewaxDatabase.get.personDao()
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
        runBlocking {
            val personDao = NewaxDatabase.get.personDao()
            val personId = personDao.findByName(personName)?.id
                ?: personDao.insertIfAbsent(PersonEntity(name = personName)).let {
                    personDao.idForName(personName) ?: return@runBlocking
                }
            if (NewaxDatabase.get.personFactDao().findExact(personId, fact) != null) return@runBlocking
            NewaxDatabase.get.personFactDao().insert(
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
    private fun entityIdFor(name: String): Long? = runBlocking {
        runCatching {
            val dao = NewaxDatabase.get.graphDao()
            dao.findByName(name)?.id
                ?: dao.insertEntity(GraphEntity(type = 0, canonicalName = name, createdAt = System.currentTimeMillis()))
        }.getOrNull()
    }

    /**
     * Incoming graph edge (item 1): names resolve to local ids, identical
     * current edges are deduped, and the LWW guard keeps the newest per
     * (subject, predicate, object) key.
     */
    private fun materializeEdge(entry: SyncEntry) {
        if (entry.tombstone || locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val subject = fields["subject"]?.takeIf { it.isNotBlank() } ?: return
        val predicate = fields["predicate"]?.takeIf { it.isNotBlank() } ?: return
        val objectName = fields["object"]?.takeIf { it.isNotBlank() }
        val objectValue = fields["objectValue"]?.takeIf { it.isNotBlank() }
        if (objectName == null && objectValue == null) return
        runBlocking {
            runCatching {
                val dao = NewaxDatabase.get.graphDao()
                val subjectId = entityIdFor(subject)
                val predicateId = dao.predicateByName(predicate)?.id
                    ?: dao.insertPredicate(GraphPredicate(name = predicate))
                val objectId = objectName?.let { entityIdFor(it) }
                if (subjectId == null || predicateId == 0L) return@runCatching
                // Two devices learning the same fact produce two journal entries
                // — skip when an identical current edge already exists.
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
        runBlocking {
            runCatching {
                NewaxDatabase.get.appRegistryDao().upsertRecord(
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
        runBlocking {
            runCatching {
                NewaxDatabase.get.appRegistryDao().upsertLink(
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
     * Incoming episode (hierarchical memory) — LWW per episodeId; a tombstone
     * deletes. Episodes are append-only facts, so insert-if-absent (upsert
     * keeps a replayed entry from duplicating).
     */
    private fun materializeEpisode(entry: SyncEntry) {
        if (locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val episodeId = fields["episodeId"]?.takeIf { it.isNotBlank() } ?: return
        runBlocking {
            runCatching {
                val dao = NewaxDatabase.get.agentMemoryDao()
                if (entry.tombstone) {
                    dao.deleteEpisode(episodeId)
                    com.newax.aegis.engine.embedding.VectorStore.removeEpisode(NewaxDatabase.get, episodeId)
                } else {
                    val episode = Episode(
                        episodeId = episodeId,
                        agentId = fields["agentId"] ?: "",
                        category = fields["category"] ?: "",
                        summary = fields["summary"] ?: "",
                        outcome = fields["outcome"] ?: EpisodeOutcome.OBSERVATION,
                        lesson = fields["lesson"] ?: "",
                        occurredAtMs = fields["occurredAtMs"]?.toLongOrNull() ?: entry.createdAt,
                        contextRef = fields["contextRef"] ?: ""
                    )
                    dao.upsertEpisode(episode)
                    com.newax.aegis.engine.embedding.VectorStore.indexEpisode(
                        NewaxDatabase.get, episodeId, episode.summary, episode.lesson, episode.outcome
                    )
                }
            }
        }
    }

    /**
     * Incoming handoff (L3 shared write) — LWW per handoffId. Status-only
     * entries (an ack from the consumer) merge into the existing row; full
     * entries insert. Tombstones delete.
     */
    private fun materializeHandoff(entry: SyncEntry) {
        if (locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val handoffId = fields["handoffId"]?.takeIf { it.isNotBlank() } ?: return
        runBlocking {
            runCatching {
                val dao = NewaxDatabase.get.agentMemoryDao()
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
                        HandoffEntry(
                            handoffId = handoffId,
                            fromAgent = fields["fromAgent"] ?: "",
                            toAgent = fields["toAgent"] ?: "",
                            task = fields["task"] ?: "",
                            summary = fields["summary"] ?: "",
                            artifactJson = fields["artifactJson"] ?: "{}",
                            status = status ?: HandoffStatus.PENDING,
                            refId = fields["refId"] ?: "",
                            createdAtMs = fields["createdAtMs"]?.toLongOrNull() ?: entry.createdAt
                        )
                    )
                }
            }
        }
    }

    /**
     * Incoming library entry (L1 gated library) — LWW per entryId. Status-only
     * entries (an approval/decision from the gate) merge into the existing
     * row; full entries insert. Tombstones delete. REJECTED entries stay in
     * the journal (audit) but are never surfaced by the read-only library.
     */
    private fun materializeLibrary(entry: SyncEntry) {
        if (locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val entryId = fields["entryId"]?.takeIf { it.isNotBlank() } ?: return
        runBlocking {
            runCatching {
                val dao = NewaxDatabase.get.agentMemoryDao()
                if (entry.tombstone) {
                    dao.deleteLibrary(entryId)
                    com.newax.aegis.engine.embedding.VectorStore.removeLibrary(NewaxDatabase.get, entryId)
                    return@runCatching
                }
                val existing = dao.libraryById(entryId)
                val status = fields["status"]?.takeIf { it.isNotBlank() }
                if (existing != null && status != null) {
                    // Decay propagation: an incoming confidence rides along with
                    // the status; reindex when the value changed.
                    val confidence = fields["confidence"]?.toIntOrNull()
                    if (confidence != null && confidence != existing.confidence) {
                        dao.updateLibraryConfidence(entryId, confidence)
                    }
                    dao.setLibraryStatus(entryId, status, System.currentTimeMillis())
                    if (status == LibraryStatus.ACTIVE) {
                        com.newax.aegis.engine.embedding.VectorStore.indexLibrary(
                            NewaxDatabase.get, entryId, existing.category, existing.title, existing.content
                        )
                    } else if (status == LibraryStatus.REJECTED) {
                        com.newax.aegis.engine.embedding.VectorStore.removeLibrary(NewaxDatabase.get, entryId)
                    }
                } else if (existing == null) {
                    val entry = LibraryEntry(
                        entryId = entryId,
                        category = fields["category"] ?: "",
                        title = fields["title"] ?: "",
                        content = fields["content"] ?: "",
                        confidence = fields["confidence"]?.toIntOrNull() ?: 80,
                        source = fields["source"] ?: "",
                        status = status ?: LibraryStatus.PENDING_APPROVAL,
                        createdAtMs = fields["createdAtMs"]?.toLongOrNull() ?: entry.createdAt
                    )
                    dao.upsertLibrary(entry)
                    if (entry.status == LibraryStatus.ACTIVE) {
                        com.newax.aegis.engine.embedding.VectorStore.indexLibrary(
                            NewaxDatabase.get, entryId, entry.category, entry.title, entry.content
                        )
                    }
                }
            }
        }
    }

    /** Incoming trigger rule (item 1) — LWW per label; tombstones delete. */
    private fun materializeTriggerRule(entry: SyncEntry) {
        if (locallyNewer(entry)) return
        val fields = SyncPayload.decode(entry.payload)
        val label = fields["label"]?.takeIf { it.isNotBlank() } ?: return
        runBlocking {
            runCatching {
                val dao = NewaxDatabase.get.triggerDao()
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

    /**
     * Re-apply MY OWN pairing/revocation records (key namespaced with my
     * device id — other devices' trust rows are someone else's business): a
     * tombstone re-removes a peer (durable revocation after reinstall), a live
     * record restores it. The LWW guard keeps a newer local decision winning.
     */
    private fun materializeTrust(entry: SyncEntry) {
        if (locallyNewer(entry)) return
        val parts = entry.key.split(TRUST_SEP)
        if (parts.size != 2 || parts[0] != deviceId()) return
        val peerId = parts[1]
        if (entry.tombstone) {
            keyStoreHolder.removePeer(peerId)
            kvDelete(ADDR_PREFIX + peerId)
            return
        }
        val fields = SyncPayload.decode(entry.payload)
        val sign = fields["signPublicKey"]?.let { Hex.decode(it) } ?: return
        val ecdh = fields["ecdhPublicKey"]?.let { Hex.decode(it) } ?: return
        keyStoreHolder.savePeer(
            PairedPeer(
                deviceId = peerId,
                displayName = fields["displayName"] ?: peerId,
                signPublicKey = sign,
                ecdhPublicKey = ecdh,
                pairedAtMs = fields["pairedAtMs"]?.toLongOrNull() ?: entry.createdAt
            )
        )
    }

    private fun materializeMemoryProfile(entry: SyncEntry) {
        val target = memory ?: return
        val array = JSONArray(entry.payload.decodeToString())
        val facts = buildList {
            for (i in 0 until array.length()) add(array.getString(i))
        }
        // applyRemoteCategory (not setCategory): remote application must NOT
        // re-journal the state it just received — that would bounce the entry
        // back and forth forever (each re-capture carries a newer HLC).
        target.applyRemoteCategory(entry.key, facts)
    }

    private fun materializeKv(entry: SyncEntry) {
        val localKey = SyncPolicy.localKey(entry.key) ?: return
        if (entry.tombstone) {
            runBlocking { runCatching { NewaxDatabase.get.kvStoreDao().delete(localKey) } }
        } else {
            runBlocking {
                runCatching { NewaxDatabase.get.kvStoreDao().put(KvStoreEntity(localKey, entry.payload.decodeToString())) }
            }
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun nextHlc(): Hlc = synchronized(hlcLock) {
        clock = Hlc.tick(clock, System.currentTimeMillis())
        clock
    }

    private fun kvGet(key: String): String? = runBlocking {
        runCatching { NewaxDatabase.get.kvStoreDao().get(key) }.getOrNull()
    }

    private fun kvPut(key: String, value: String) {
        runBlocking { runCatching { NewaxDatabase.get.kvStoreDao().put(KvStoreEntity(key, value)) } }
    }

    private fun kvDelete(key: String) {
        runBlocking { runCatching { NewaxDatabase.get.kvStoreDao().delete(key) } }
    }
}
