package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.LearningDraftEntity

@Dao
interface LearningDraftDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(draft: LearningDraftEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(drafts: List<LearningDraftEntity>)

    @Query("SELECT * FROM learning_drafts WHERE status = 'PENDING' ORDER BY confidence DESC, timestampMs DESC")
    fun getPending(): List<LearningDraftEntity>

    @Query("SELECT * FROM learning_drafts ORDER BY timestampMs DESC")
    fun getAll(): List<LearningDraftEntity>

    @Query("SELECT * FROM learning_drafts WHERE id = :id LIMIT 1")
    fun findById(id: String): LearningDraftEntity?

    @Query("UPDATE learning_drafts SET status = :status WHERE id = :id")
    fun updateStatus(id: String, status: String)

    @Query("UPDATE learning_drafts SET status = 'APPROVED' WHERE status = 'PENDING'")
    fun approveAll()

    @Query("UPDATE learning_drafts SET status = 'REJECTED' WHERE status = 'PENDING'")
    fun rejectAll()

    @Query("SELECT COUNT(*) FROM learning_drafts WHERE status = 'PENDING'")
    fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM learning_drafts WHERE status = :status")
    fun countByStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM learning_drafts")
    fun total(): Int

    /** Delete all non-pending drafts older than :beforeMs to keep storage tidy. */
    @Query("DELETE FROM learning_drafts WHERE status != 'PENDING' AND timestampMs < :beforeMs")
    fun pruneOldProcessed(beforeMs: Long)

    @Query("DELETE FROM learning_drafts WHERE status != 'PENDING'")
    fun clearNonPending()

    @Query("DELETE FROM learning_drafts")
    fun clearAll()

    @Query("SELECT * FROM learning_drafts WHERE subjectName = :name ORDER BY timestampMs DESC")
    fun forSubject(name: String): List<LearningDraftEntity>
}
