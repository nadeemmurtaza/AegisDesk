package com.newax.aegis

import android.content.Context
import android.os.Build
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.KvStoreEntity
import com.newax.aegis.db.sync.toEntity
import com.newax.aegis.memory.EncryptedMemory
import com.newax.aegis.sync.Crypto
import com.newax.aegis.sync.Hex
import com.newax.aegis.sync.Hlc
import com.newax.aegis.sync.Identity
import com.newax.aegis.sync.KeyStore
import com.newax.aegis.sync.PairedPeer
import com.newax.aegis.sync.Pairing
import com.newax.aegis.sync.PairingRequest
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

    /** Local-only kv_store keys (NOT under `syncable:` — they never sync). */
    private const val KEY_ENABLED = "sync:enabled"
    private const val KEY_STATUS = "sync:status"
    private const val ADDR_PREFIX = "sync:addr:"

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
            runCatching { AegisDatabase.get.kvStoreDao().getWithPrefix(ADDR_PREFIX) }.getOrNull().orEmpty()
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

    fun capture(
        kind: SyncEntry.Kind,
        table: String,
        key: String,
        payload: ByteArray,
        tombstone: Boolean = false
    ) {
        if (!enabled()) return
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
                AegisDatabase.get.syncJournalDao().insert(entry.toEntity())
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
            if (entry.kind != SyncEntry.Kind.RECORD) continue
            try {
                when {
                    entry.table == TABLE_MEMORY_PROFILE && !entry.tombstone ->
                        materializeMemoryProfile(entry)

                    entry.table == "kv_store" && SyncPolicy.isSyncableKey(entry.key) ->
                        materializeKv(entry)

                    else -> Unit // tables not yet captured/materialized
                }
            } catch (_: Exception) {
                // A malformed payload must not kill the rest of the round.
            }
        }
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
            runBlocking { runCatching { AegisDatabase.get.kvStoreDao().delete(localKey) } }
        } else {
            runBlocking {
                runCatching { AegisDatabase.get.kvStoreDao().put(KvStoreEntity(localKey, entry.payload.decodeToString())) }
            }
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun nextHlc(): Hlc = synchronized(hlcLock) {
        clock = Hlc.tick(clock, System.currentTimeMillis())
        clock
    }

    private fun kvGet(key: String): String? = runBlocking {
        runCatching { AegisDatabase.get.kvStoreDao().get(key) }.getOrNull()
    }

    private fun kvPut(key: String, value: String) {
        runBlocking { runCatching { AegisDatabase.get.kvStoreDao().put(KvStoreEntity(key, value)) } }
    }

    private fun kvDelete(key: String) {
        runBlocking { runCatching { AegisDatabase.get.kvStoreDao().delete(key) } }
    }
}
