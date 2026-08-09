package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.EmbeddingEntity

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EmbeddingEntity)

    @Query("SELECT * FROM embeddings")
    suspend fun getAll(): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE sourceType = :type")
    suspend fun getByType(type: String): List<EmbeddingEntity>

    @Query("SELECT sourceId FROM embeddings WHERE sourceType = :type")
    suspend fun getSourceIds(type: String): List<String>

    @Query("SELECT COUNT(*) FROM embeddings")
    suspend fun count(): Int

    @Query("DELETE FROM embeddings WHERE sourceType = :type AND sourceId = :sourceId")
    suspend fun deleteBySource(type: String, sourceId: String): Int

    @Query("DELETE FROM embeddings WHERE sourceType = 'fact' AND sourceId NOT IN (SELECT CAST(id AS TEXT) FROM person_facts)")
    suspend fun pruneOrphans(): Int
}
