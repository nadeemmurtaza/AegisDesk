package com.newax.aegis.engine.learning

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.LearningDraftEntity

/**
 * Persistent draft store backed by Room + SQLCipher.
 * Each draft is a row in `learning_drafts`; no JSON blob, no single-lock serialization.
 * Public API mirrors the old EncryptedMemory-based version — callers need only swap
 * the first parameter from `EncryptedMemory` to `AegisDatabase`.
 */
object DraftStore {

    private const val MAX_STORED = 500

    fun addDraft(db: AegisDatabase, draft: LearningDraft) {
        db.learningDraftDao().insert(draft.toEntity())
        pruneIfNeeded(db)
    }

    fun addDrafts(db: AegisDatabase, drafts: List<LearningDraft>) {
        if (drafts.isEmpty()) return
        db.learningDraftDao().insertAll(drafts.map { it.toEntity() })
        pruneIfNeeded(db)
    }

    fun pending(db: AegisDatabase): List<LearningDraft> =
        db.learningDraftDao().getPending().map { it.toDraft() }

    fun all(db: AegisDatabase): List<LearningDraft> =
        db.learningDraftDao().getAll().map { it.toDraft() }

    fun getById(db: AegisDatabase, id: String): LearningDraft? =
        db.learningDraftDao().findById(id)?.toDraft()

    fun approveDraft(db: AegisDatabase, id: String): LearningDraft? {
        db.learningDraftDao().updateStatus(id, "APPROVED")
        return db.learningDraftDao().findById(id)?.toDraft()
    }

    fun rejectDraft(db: AegisDatabase, id: String) =
        db.learningDraftDao().updateStatus(id, "REJECTED")

    fun approveAll(db: AegisDatabase): List<LearningDraft> {
        val pending = db.learningDraftDao().getPending().map { it.toDraft() }
        db.learningDraftDao().approveAll()
        return pending
    }

    fun rejectAll(db: AegisDatabase) =
        db.learningDraftDao().rejectAll()

    fun pendingCount(db: AegisDatabase): Int =
        db.learningDraftDao().pendingCount()

    data class DraftStats(val total: Int, val pending: Int, val approved: Int, val rejected: Int)

    fun stats(db: AegisDatabase): DraftStats = DraftStats(
        total    = db.learningDraftDao().total(),
        pending  = db.learningDraftDao().countByStatus("PENDING"),
        approved = db.learningDraftDao().countByStatus("APPROVED"),
        rejected = db.learningDraftDao().countByStatus("REJECTED")
    )

    fun pruneOld(db: AegisDatabase) =
        db.learningDraftDao().clearNonPending()

    fun clearOld(db: AegisDatabase, keepPending: Boolean = true) {
        if (keepPending) db.learningDraftDao().clearNonPending()
        else db.learningDraftDao().clearAll()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun pruneIfNeeded(db: AegisDatabase) {
        val total = db.learningDraftDao().total()
        if (total > MAX_STORED) {
            // Remove oldest non-pending drafts first
            val cutoffMs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            db.learningDraftDao().pruneOldProcessed(cutoffMs)
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
