package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.MemoryRecord

@Dao
interface MemoryRecordDao {

    @Insert
    suspend fun insert(record: MemoryRecord): Long

    @Query("SELECT * FROM memory_records WHERE contentHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): MemoryRecord?

    @Query("SELECT * FROM memory_records WHERE LOWER(subject) = LOWER(:subject) AND validUntil IS NULL ORDER BY importance DESC LIMIT :limit")
    suspend fun findBySubject(subject: String, limit: Int = 20): List<MemoryRecord>

    @Query("SELECT * FROM memory_records WHERE type = :type AND validUntil IS NULL ORDER BY importance DESC, createdAt DESC LIMIT :limit")
    suspend fun findByType(type: Int, limit: Int = 50): List<MemoryRecord>

    @Query("SELECT * FROM memory_records WHERE category = :category AND validUntil IS NULL ORDER BY importance DESC LIMIT :limit")
    suspend fun findByCategory(category: String, limit: Int = 30): List<MemoryRecord>

    @Query("SELECT * FROM memory_records WHERE createdAt >= :fromMs AND createdAt <= :untilMs AND validUntil IS NULL ORDER BY importance DESC LIMIT :limit")
    suspend fun findByTimeRange(fromMs: Long, untilMs: Long, limit: Int = 20): List<MemoryRecord>

    @Query("SELECT * FROM memory_records WHERE validUntil IS NULL ORDER BY importance DESC, createdAt DESC LIMIT :limit")
    suspend fun current(limit: Int = 100): List<MemoryRecord>

    @Query("UPDATE memory_records SET validUntil = :now WHERE id = :id")
    suspend fun invalidate(id: Long, now: Long): Int

    @Query("UPDATE memory_records SET importance = :importance, updatedAt = :now WHERE id = :id")
    suspend fun bumpImportance(id: Long, importance: Int, now: Long): Int

    @Query("UPDATE memory_records SET embeddingId = :sourceId WHERE id = :id")
    suspend fun updateEmbeddingId(id: Long, sourceId: String): Int

    @Query("UPDATE memory_records SET graphEdgeId = :edgeId WHERE id = :id")
    suspend fun updateGraphEdgeId(id: Long, edgeId: Long): Int

    @Query("SELECT COUNT(*) FROM memory_records WHERE validUntil IS NULL")
    suspend fun count(): Int
}
