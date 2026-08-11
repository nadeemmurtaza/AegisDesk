package com.newax.aegis.sync

/**
 * The journal + version-vector store the sync round drives — the seam between
 * AntiEntropyRunner and persistence. The wiring slice implements this over
 * SyncJournalDao/SyncVectorDao (schema v13, slice S0); [InMemoryJournalStore]
 * serves tests and the pure-KMP path (Track I).
 *
 * Contract for implementations: [entries] returns the full journal sorted by
 * (hlc, deviceId) — the order the delta must preserve; [existingOpIds] is the
 * dedup set (the opId CRDT); [append] is idempotent per opId; watermarks are
 * per-peer high-water marks that only ever move forward.
 */
interface JournalStore {

    fun myDeviceId(): String

    /** Full journal, sorted by (hlc, deviceId) — the delta order. */
    fun entries(): List<SyncEntry>

    /** Every opId ever seen — the dedup set. */
    fun existingOpIds(): Set<String>

    /** Persist new entries (dedup by opId is the caller's contract). */
    fun append(entries: List<SyncEntry>)

    /** My watermark for [peerDeviceId] — ZERO when never synced (send everything). */
    fun watermarkFor(peerDeviceId: String): Hlc

    /** All my watermarks — the VECTOR_EXCH payload. */
    fun watermarks(): Map<String, Hlc>

    /** Advance my watermark for [peerDeviceId]; never moves backwards. */
    fun setWatermark(peerDeviceId: String, hlc: Hlc)
}

/** Process-local journal store — tests and pure-KMP use. */
class InMemoryJournalStore(
    private val deviceId: String
) : JournalStore {

    private val journal = LinkedHashMap<String, SyncEntry>()
    private val watermarks = HashMap<String, Hlc>()

    override fun myDeviceId(): String = deviceId

    override fun entries(): List<SyncEntry> =
        journal.values.sortedWith(compareBy({ it.hlc }, { it.deviceId }))

    override fun existingOpIds(): Set<String> = journal.keys

    override fun append(entries: List<SyncEntry>) {
        entries.forEach { journal.putIfAbsent(it.opId, it) }
    }

    override fun watermarkFor(peerDeviceId: String): Hlc =
        watermarks[peerDeviceId] ?: Hlc.ZERO

    override fun watermarks(): Map<String, Hlc> = HashMap(watermarks)

    override fun setWatermark(peerDeviceId: String, hlc: Hlc) {
        val current = watermarks[peerDeviceId] ?: Hlc.ZERO
        if (hlc > current) watermarks[peerDeviceId] = hlc
    }
}
