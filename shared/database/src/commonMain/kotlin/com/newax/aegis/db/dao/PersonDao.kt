package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.PersonEntity

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(person: PersonEntity): Long

    @Query("SELECT id FROM persons WHERE name = :name")
    suspend fun idForName(name: String): Long?

    @Query("SELECT * FROM persons WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): PersonEntity?

    @Query("SELECT * FROM persons WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): PersonEntity?

    @Query("SELECT * FROM persons ORDER BY importanceScore DESC LIMIT :limit")
    suspend fun getTopPeople(limit: Int): List<PersonEntity>

    @Query("""
        UPDATE persons
        SET sourceCount = :sourceCount,
            totalMentions = :totalMentions,
            importanceScore = :importanceScore,
            lastSeenMs = :lastSeenMs
        WHERE id = :id
    """)
    suspend fun updateStats(id: Long, sourceCount: Int, totalMentions: Int, importanceScore: Float, lastSeenMs: Long)

    @Query("UPDATE persons SET profileBuilt = 1 WHERE name = :name")
    suspend fun markProfileBuilt(name: String): Int

    @Query("""
        SELECT * FROM persons
        WHERE profileBuilt = 0
          AND (sourceCount >= :crossSourceThreshold OR totalMentions >= :totalThreshold)
    """)
    suspend fun getPeopleNeedingProfileBuild(crossSourceThreshold: Int, totalThreshold: Int): List<PersonEntity>

    @Query("SELECT COUNT(*) FROM persons")
    suspend fun count(): Int
}
