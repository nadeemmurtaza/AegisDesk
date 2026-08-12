package com.newax.aegis.engine.learning

import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.db.entity.LearningDraftEntity

/**
 * Persistent draft store backed by Room + SQLCipher.
 * Each draft is a row in `learning_drafts`; no JSON blob, no single-lock serialization.
 * Public API mirrors the old EncryptedMemory-based version — callers need only swap
 * the first parameter from `EncryptedMemory` to `NewaxDatabase`.
 */
object DraftStore {

    private const val MAX_STORED = 500

    fun addDraft(db: NewaxDatabase, draft: LearningDraft) {
        kotlinx.coroutines.runBlocking { db.learningDraftDao().insert(draft.toEntity()) }
        pruneIfNeeded(db)
    }

    fun addDrafts(db: NewaxDatabase, drafts: List<LearningDraft>) {
        if (drafts.isEmpty()) return
        kotlinx.coroutines.runBlocking { db.learningDraftDao().insertAll(drafts.map { it.toEntity() }) }
        pruneIfNeeded(db)
    }

    fun pending(db: NewaxDatabase): List<LearningDraft> =
        kotlinx.coroutines.runBlocking { db.learningDraftDao().getPending().map { it.toDraft() } }

    fun all(db: NewaxDatabase): List<LearningDraft> =
        kotlinx.coroutines.runBlocking { db.learningDraftDao().getAll().map { it.toDraft() } }

    fun getById(db: NewaxDatabase, id: String): LearningDraft? =
        kotlinx.coroutines.runBlocking { db.learningDraftDao().findById(id)?.toDraft() }

    fun approveDraft(db: NewaxDatabase, id: String): LearningDraft? {
        return kotlinx.coroutines.runBlocking {
            db.learningDraftDao().updateStatus(id, "APPROVED")
            db.learningDraftDao().findById(id)?.toDraft()
        }
    }

    fun rejectDraft(db: NewaxDatabase, id: String) =
        kotlinx.coroutines.runBlocking { db.learningDraftDao().updateStatus(id, "REJECTED") }

    fun approveAll(db: NewaxDatabase): List<LearningDraft> {
        return kotlinx.coroutines.runBlocking {
            val pending = db.learningDraftDao().getPending().map { it.toDraft() }
            db.learningDraftDao().approveAll()
            pending
        }
    }

    fun rejectAll(db: NewaxDatabase) =
        kotlinx.coroutines.runBlocking { db.learningDraftDao().rejectAll() }

    fun pendingCount(db: NewaxDatabase): Int =
        kotlinx.coroutines.runBlocking { db.learningDraftDao().pendingCount() }

    data class DraftStats(val total: Int, val pending: Int, val approved: Int, val rejected: Int)

    fun stats(db: NewaxDatabase): DraftStats = kotlinx.coroutines.runBlocking {
        DraftStats(
            total    = db.learningDraftDao().total(),
            pending  = db.learningDraftDao().countByStatus("PENDING"),
            approved = db.learningDraftDao().countByStatus("APPROVED"),
            rejected = db.learningDraftDao().countByStatus("REJECTED")
        )
    }

    fun pruneOld(db: NewaxDatabase) =
        kotlinx.coroutines.runBlocking { db.learningDraftDao().clearNonPending() }

    fun clearOld(db: NewaxDatabase, keepPending: Boolean = true) {
        kotlinx.coroutines.runBlocking {
            if (keepPending) db.learningDraftDao().clearNonPending()
            else db.learningDraftDao().clearAll()
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun pruneIfNeeded(db: NewaxDatabase) {
        kotlinx.coroutines.runBlocking {
            val total = db.learningDraftDao().total()
            if (total > MAX_STORED) {
                // Remove oldest non-pending drafts first
                val cutoffMs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                db.learningDraftDao().pruneOldProcessed(cutoffMs)
            }
        }
    }

    private fun LearningDraft.toEntity() = LearningDraftEntity(
        id            = id,
        category      = category,
        fact          = fact,
        source        = source,
        sourceSnippet = sourceSnippet,
        confidence    = confidence,
        status        = status.name,
        subjectName   = subjectName,
        timestampMs   = timestampMs
    )

    private fun LearningDraftEntity.toDraft() = LearningDraft(
        id            = id,
        category      = category,
        fact          = fact,
        source        = source,
        sourceSnippet = sourceSnippet,
        confidence    = confidence,
        timestampMs   = timestampMs,
        status        = runCatching { LearningDraft.Status.valueOf(status) }.getOrDefault(LearningDraft.Status.PENDING),
        subjectName   = subjectName
    )
}
