package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.LearningDraftEntity

@Dao
interface LearningDraftDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(draft: LearningDraftEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(drafts: List<LearningDraftEntity>)

    @Query("SELECT * FROM learning_drafts WHERE status = 'PENDING' ORDER BY confidence DESC, timestampMs DESC")
    suspend fun getPending(): List<LearningDraftEntity>

    @Query("SELECT * FROM learning_drafts ORDER BY timestampMs DESC")
    suspend fun getAll(): List<LearningDraftEntity>

    @Query("SELECT * FROM learning_drafts WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): LearningDraftEntity?

    @Query("UPDATE learning_drafts SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String): Int

    @Query("UPDATE learning_drafts SET status = 'APPROVED' WHERE status = 'PENDING'")
    suspend fun approveAll(): Int

    @Query("UPDATE learning_drafts SET status = 'REJECTED' WHERE status = 'PENDING'")
    suspend fun rejectAll(): Int

    @Query("SELECT COUNT(*) FROM learning_drafts WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM learning_drafts WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM learning_drafts")
    suspend fun total(): Int

    /** Delete all non-pending drafts older than :beforeMs to keep storage tidy. */
    @Query("DELETE FROM learning_drafts WHERE status != 'PENDING' AND timestampMs < :beforeMs")
    suspend fun pruneOldProcessed(beforeMs: Long): Int

    @Query("DELETE FROM learning_drafts WHERE status != 'PENDING'")
    suspend fun clearNonPending(): Int

    @Query("DELETE FROM learning_drafts")
    suspend fun clearAll(): Int

    @Query("SELECT * FROM learning_drafts WHERE subjectName = :name ORDER BY timestampMs DESC")
    suspend fun forSubject(name: String): List<LearningDraftEntity>
}
