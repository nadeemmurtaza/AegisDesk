package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.TripleEntity

@Dao
interface TripleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(triple: TripleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(triples: List<TripleEntity>)

    @Query("SELECT * FROM triples WHERE subject = :subject ORDER BY confidence DESC")
    fun bySubject(subject: String): List<TripleEntity>

    @Query("SELECT * FROM triples WHERE subject = :subject AND predicate = :predicate ORDER BY confidence DESC")
    fun bySubjectPredicate(subject: String, predicate: String): List<TripleEntity>

    @Query("SELECT * FROM triples WHERE predicate = :predicate ORDER BY confidence DESC LIMIT :limit")
    fun byPredicate(predicate: String, limit: Int = 50): List<TripleEntity>

    @Query("SELECT * FROM triples WHERE objectValue = :obj ORDER BY confidence DESC LIMIT :limit")
    fun byObject(obj: String, limit: Int = 50): List<TripleEntity>

    @Query("SELECT * FROM triples WHERE subject = :entity OR objectValue = :entity ORDER BY confidence DESC")
    fun involving(entity: String): List<TripleEntity>

    @Query("SELECT DISTINCT subject FROM triples ORDER BY subject")
    fun allSubjects(): List<String>

    @Query("SELECT COUNT(*) FROM triples")
    fun count(): Int

    @Query("DELETE FROM triples WHERE subject = :subject")
    fun deleteBySubject(subject: String)
}
