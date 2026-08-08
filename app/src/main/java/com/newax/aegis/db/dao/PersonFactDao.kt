package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.PersonFactEntity

@Dao
interface PersonFactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(fact: PersonFactEntity): Long

    @Query("SELECT * FROM person_facts WHERE personId = :personId ORDER BY timestampMs DESC")
    fun forPerson(personId: Long): List<PersonFactEntity>

    @Query("SELECT * FROM person_facts WHERE personId = :personId AND category = :category ORDER BY timestampMs DESC")
    fun forPersonByCategory(personId: Long, category: String): List<PersonFactEntity>

    @Query("SELECT COUNT(*) FROM person_facts WHERE personId = :personId")
    fun countForPerson(personId: Long): Int

    /** Full-text search via FTS4 virtual table (created by AegisDatabase.Callback). */
    @SkipQueryVerification
    @Query("""
        SELECT * FROM person_facts
        WHERE id IN (SELECT rowid FROM person_facts_fts WHERE person_facts_fts MATCH :query)
        ORDER BY timestampMs DESC
        LIMIT :limit
    """)
    fun searchFts(query: String, limit: Int): List<PersonFactEntity>

    /** Keep only the most recent :keepCount facts per person; delete older ones. */
    @Query("""
        DELETE FROM person_facts
        WHERE personId = :personId
          AND id NOT IN (
              SELECT id FROM person_facts
              WHERE personId = :personId
              ORDER BY timestampMs DESC
              LIMIT :keepCount
          )
    """)
    fun trimToLimit(personId: Long, keepCount: Int)

    @Query("SELECT * FROM person_facts WHERE personId = :personId AND fact = :fact LIMIT 1")
    fun findExact(personId: Long, fact: String): PersonFactEntity?

    @Query("SELECT id FROM person_facts")
    fun getAllIds(): List<Long>

    @Query("SELECT * FROM person_facts WHERE id IN (:ids)")
    fun getByIds(ids: List<Long>): List<PersonFactEntity>
}
