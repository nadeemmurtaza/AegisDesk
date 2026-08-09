package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.TripleEntity

@Dao
interface TripleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(triple: TripleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(triples: List<TripleEntity>)

    @Query("SELECT * FROM triples WHERE subject = :subject ORDER BY confidence DESC")
    suspend fun bySubject(subject: String): List<TripleEntity>

    @Query("SELECT * FROM triples WHERE subject = :subject AND predicate = :predicate ORDER BY confidence DESC")
    suspend fun bySubjectPredicate(subject: String, predicate: String): List<TripleEntity>

    @Query("SELECT * FROM triples WHERE predicate = :predicate ORDER BY confidence DESC LIMIT :limit")
    suspend fun byPredicate(predicate: String, limit: Int = 50): List<TripleEntity>

    @Query("SELECT * FROM triples WHERE objectValue = :obj ORDER BY confidence DESC LIMIT :limit")
    suspend fun byObject(obj: String, limit: Int = 50): List<TripleEntity>

    @Query("SELECT * FROM triples WHERE subject = :entity OR objectValue = :entity ORDER BY confidence DESC")
    suspend fun involving(entity: String): List<TripleEntity>

    @Query("SELECT DISTINCT subject FROM triples ORDER BY subject")
    suspend fun allSubjects(): List<String>

    @Query("SELECT COUNT(*) FROM triples")
    suspend fun count(): Int

    @Query("SELECT * FROM triples WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): TripleEntity?

    @Query("SELECT * FROM triples ORDER BY createdMs DESC LIMIT :limit")
    suspend fun getAll(limit: Int = 5000): List<TripleEntity>

    @Query("DELETE FROM triples WHERE subject = :subject")
    suspend fun deleteBySubject(subject: String): Int
}
