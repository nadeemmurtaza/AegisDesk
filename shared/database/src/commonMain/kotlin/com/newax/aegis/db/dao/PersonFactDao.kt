package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.PersonFactEntity

@Dao
interface PersonFactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: PersonFactEntity): Long

    @Query("SELECT * FROM person_facts WHERE personId = :personId ORDER BY timestampMs DESC")
    suspend fun forPerson(personId: Long): List<PersonFactEntity>

    @Query("SELECT * FROM person_facts WHERE personId = :personId AND category = :category ORDER BY timestampMs DESC")
    suspend fun forPersonByCategory(personId: Long, category: String): List<PersonFactEntity>

    @Query("SELECT COUNT(*) FROM person_facts WHERE personId = :personId")
    suspend fun countForPerson(personId: Long): Int

    /**
     * Full-text search over the `person_facts_fts` FTS4 table.
     *
     * That table is a **Room-managed** `@Fts4(contentEntity = PersonFactEntity::class)`
     * entity ([PersonFactFts]), declared in `NewaxDatabase` — which is why this query
     * compiles without `@SkipQueryVerification`: Room knows the table and verifies the
     * SQL. It was hand-rolled by `FtsSetupCallback` until migration 11→12 replaced it;
     * this comment said so for four schema versions after it stopped being true.
     */
    @Query("""
        SELECT * FROM person_facts
        WHERE id IN (SELECT rowid FROM person_facts_fts WHERE person_facts_fts MATCH :query)
        ORDER BY timestampMs DESC
        LIMIT :limit
    """)
    suspend fun searchFts(query: String, limit: Int): List<PersonFactEntity>

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
    suspend fun trimToLimit(personId: Long, keepCount: Int)

    @Query("SELECT * FROM person_facts WHERE personId = :personId AND fact = :fact LIMIT 1")
    suspend fun findExact(personId: Long, fact: String): PersonFactEntity?

    @Query("SELECT id FROM person_facts")
    suspend fun getAllIds(): List<Long>

    @Query("SELECT * FROM person_facts WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<PersonFactEntity>
}
