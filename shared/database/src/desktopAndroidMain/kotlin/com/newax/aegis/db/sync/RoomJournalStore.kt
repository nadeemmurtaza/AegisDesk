package com.newax.aegis.db.sync

import com.newax.aegis.db.dao.SyncJournalDao
import com.newax.aegis.db.dao.SyncVectorDao
import com.newax.aegis.db.entity.SyncJournalEntity
import com.newax.aegis.db.entity.SyncVectorEntity
import com.newax.aegis.sync.Hlc
import com.newax.aegis.sync.JournalStore
import com.newax.aegis.sync.SyncEntry
import kotlinx.coroutines.runBlocking

/**
 * The wiring slice (docs/SYNC_DESIGN.md §4, S0/S1): the [JournalStore] seam
 * implemented over the Room `sync_journal` / `sync_vector` tables. This is the
 * production persistence behind the anti-entropy loop — previously the only
 * implementation was [com.newax.aegis.sync.InMemoryJournalStore] for tests.
 *
 * The DAOs are suspend; [JournalStore] is a blocking interface driven from the
 * transport's own threads, so each call bridges with [runBlocking]. Callers
 * must never invoke this from the Android main thread (the sync worker and
 * transport threads are background by construction).
 *
 * Semantics preserved exactly:
 *  - [append] dedups by opId (Room ON CONFLICT IGNORE) — the CRDT guarantee.
 *  - watermarks only ever advance (SyncVectorDao REPLACE per peer).
 *  - [entries] returns the full journal ordered by (hlc, deviceId) — the
 *    delta order the engine and the wire depend on.
 */
class RoomJournalStore(
    private val journalDao: SyncJournalDao,
    private val vectorDao: SyncVectorDao,
    private val myDeviceId: String
) : JournalStore {

    override fun myDeviceId(): String = myDeviceId

    override fun entries(): List<SyncEntry> = runBlocking {
        journalDao.getAll()
            .sortedWith(compareBy({ it.hlcWall }, { it.hlcCounter }, { it.deviceId }))
            .map { it.toSyncEntry() }
    }

    override fun existingOpIds(): Set<String> = runBlocking {
        journalDao.getAll().map { it.opId }.toSet()
    }

    override fun append(entries: List<SyncEntry>) {
        if (entries.isEmpty()) return
        runBlocking {
            journalDao.insertAll(entries.map { it.toEntity() })
        }
    }

    override fun watermarkFor(peerDeviceId: String): Hlc = runBlocking {
        vectorDao.getByPeer(peerDeviceId)?.let { Hlc(it.lastAppliedHlcWall, it.lastAppliedHlcCounter) }
            ?: Hlc.ZERO
    }

    override fun watermarks(): Map<String, Hlc> = runBlocking {
        vectorDao.getAll().associate { it.peerDeviceId to Hlc(it.lastAppliedHlcWall, it.lastAppliedHlcCounter) }
    }

    override fun setWatermark(peerDeviceId: String, hlc: Hlc) {
        runBlocking {
            vectorDao.upsert(
                SyncVectorEntity(
                    peerDeviceId = peerDeviceId,
                    lastAppliedHlcWall = hlc.wall,
                    lastAppliedHlcCounter = hlc.counter
                )
            )
        }
    }

}

/** Room entity → engine entry (public so app-level capture/materialize reuses it). */
fun SyncJournalEntity.toSyncEntry(): SyncEntry = SyncEntry(
    opId = opId,
    deviceId = deviceId,
    hlc = Hlc(hlcWall, hlcCounter),
    kind = if (kind == SyncJournalEntity.KIND_LOG) SyncEntry.Kind.LOG else SyncEntry.Kind.RECORD,
    table = tableName,
    key = key,
    payload = payload,
    tombstone = tombstone,
    createdAt = createdAt
)

/** Engine entry → Room entity (public so app-level capture reuses it). */
fun SyncEntry.toEntity(): SyncJournalEntity = SyncJournalEntity(
    opId = opId,
    deviceId = deviceId,
    hlcWall = hlc.wall,
    hlcCounter = hlc.counter,
    kind = if (kind == SyncEntry.Kind.LOG) SyncJournalEntity.KIND_LOG else SyncJournalEntity.KIND_RECORD,
    tableName = table,
    key = key,
    payload = payload,
    tombstone = tombstone,
    createdAt = createdAt
)
