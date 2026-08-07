package com.newax.aegis.engine.learning

import com.newax.aegis.memory.EncryptedMemory

/**
 * Encrypted persistent store for pending learning drafts.
 * Drafts accumulate here until the user approves or rejects each one.
 * All storage goes through EncryptedMemory so values never appear in plaintext.
 */
object DraftStore {
    private const val KEY = "learning_drafts"
    private const val MAX_STORED = 500
    private val lock = Any()

    fun addDraft(memory: EncryptedMemory, draft: LearningDraft) = synchronized(lock) {
        val list = loadAll(memory).toMutableList()
        // Deduplicate: skip if identical fact from same source already pending
        val alreadyExists = list.any { it.status == LearningDraft.Status.PENDING && it.fact == draft.fact && it.source == draft.source }
        if (!alreadyExists) {
            list.add(draft)
            save(memory, list)
        }
    }

    fun addDrafts(memory: EncryptedMemory, drafts: List<LearningDraft>) = synchronized(lock) {
        if (drafts.isEmpty()) return
        val list = loadAll(memory).toMutableList()
        val existingKeys = list.filter { it.status == LearningDraft.Status.PENDING }
            .map { "${it.fact}|${it.source}" }.toSet()
        val fresh = drafts.filter { "${it.fact}|${it.source}" !in existingKeys }
        if (fresh.isNotEmpty()) {
            list.addAll(fresh)
            save(memory, list)
        }
    }

    fun pending(memory: EncryptedMemory): List<LearningDraft> =
        loadAll(memory).filter { it.status == LearningDraft.Status.PENDING }
            .sortedByDescending { it.confidence }

    fun all(memory: EncryptedMemory): List<LearningDraft> = loadAll(memory)

    fun getById(memory: EncryptedMemory, id: String): LearningDraft? =
        loadAll(memory).firstOrNull { it.id == id }

    fun approveDraft(memory: EncryptedMemory, id: String): LearningDraft? = synchronized(lock) {
        val list = loadAll(memory).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx == -1) return null
        val updated = list[idx].copy(status = LearningDraft.Status.APPROVED)
        list[idx] = updated
        save(memory, list)
        updated
    }

    fun rejectDraft(memory: EncryptedMemory, id: String) = synchronized(lock) {
        val list = loadAll(memory).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx != -1) {
            list[idx] = list[idx].copy(status = LearningDraft.Status.REJECTED)
            save(memory, list)
        }
    }

    fun approveAll(memory: EncryptedMemory): List<LearningDraft> = synchronized(lock) {
        val list = loadAll(memory).toMutableList()
        val approved = mutableListOf<LearningDraft>()
        list.forEachIndexed { i, d ->
            if (d.status == LearningDraft.Status.PENDING) {
                list[i] = d.copy(status = LearningDraft.Status.APPROVED)
                approved += list[i]
            }
        }
        save(memory, list)
        approved
    }

    fun rejectAll(memory: EncryptedMemory) = synchronized(lock) {
        val list = loadAll(memory).map { d ->
            if (d.status == LearningDraft.Status.PENDING) d.copy(status = LearningDraft.Status.REJECTED) else d
        }
        save(memory, list)
    }

    fun pendingCount(memory: EncryptedMemory): Int = loadAll(memory).count { it.status == LearningDraft.Status.PENDING }

    data class DraftStats(val total: Int, val pending: Int, val approved: Int, val rejected: Int)

    fun stats(memory: EncryptedMemory): DraftStats {
        val all = loadAll(memory)
        return DraftStats(
            total    = all.size,
            pending  = all.count { it.status == LearningDraft.Status.PENDING },
            approved = all.count { it.status == LearningDraft.Status.APPROVED },
            rejected = all.count { it.status == LearningDraft.Status.REJECTED }
        )
    }

    /** Remove all non-pending drafts to keep storage small. Call periodically. */
    fun pruneOld(memory: EncryptedMemory) = synchronized(lock) {
        val list = loadAll(memory).filter { it.status == LearningDraft.Status.PENDING }
        save(memory, list)
    }

    /** Delete all drafts. If keepPending=true, preserves PENDING ones. */
    fun clearOld(memory: EncryptedMemory, keepPending: Boolean = true) = synchronized(lock) {
        val list = if (keepPending)
            loadAll(memory).filter { it.status == LearningDraft.Status.PENDING }
        else
            emptyList()
        memory.storeRaw(KEY, LearningDraft.listToJson(list))
    }

    private fun loadAll(memory: EncryptedMemory): List<LearningDraft> {
        val raw = memory.getRaw(KEY) ?: return emptyList()
        return LearningDraft.listFromJson(raw)
    }

    private fun save(memory: EncryptedMemory, drafts: List<LearningDraft>) {
        // Keep at most MAX_STORED; always preserve PENDING ones, trim old APPROVED/REJECTED
        val pending   = drafts.filter { it.status == LearningDraft.Status.PENDING }
        val processed = drafts.filter { it.status != LearningDraft.Status.PENDING }
            .takeLast(MAX_STORED - pending.size)
        memory.storeRaw(KEY, LearningDraft.listToJson(pending + processed))
    }
}
