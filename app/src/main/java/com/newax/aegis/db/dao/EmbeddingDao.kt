package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.EmbeddingEntity

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: EmbeddingEntity)

    @Query("SELECT * FROM embeddings")
    fun getAll(): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE sourceType = :type")
    fun getByType(type: String): List<EmbeddingEntity>

    @Query("SELECT sourceId FROM embeddings WHERE sourceType = :type")
    fun getSourceIds(type: String): List<String>

    @Query("SELECT COUNT(*) FROM embeddings")
    fun count(): Int

    @Query("DELETE FROM embeddings WHERE sourceType = :type AND sourceId = :sourceId")
    fun deleteBySource(type: String, sourceId: String)

    @Query("DELETE FROM embeddings WHERE sourceType = 'fact' AND sourceId NOT IN (SELECT CAST(id AS TEXT) FROM person_facts)")
    fun pruneOrphans()
}
