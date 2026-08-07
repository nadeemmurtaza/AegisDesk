package com.newax.aegis.db.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface PersonMentionDao {

    /** Increment count for this person+source pair, inserting with count=1 if absent. */
    @Query("""
        INSERT OR REPLACE INTO person_mentions(personId, sourceName, count)
        VALUES(:personId, :source,
            COALESCE((SELECT count FROM person_mentions
                      WHERE personId = :personId AND sourceName = :source), 0) + 1)
    """)
    fun incrementOrInsert(personId: Long, source: String)

    @Query("SELECT COUNT(DISTINCT sourceName) FROM person_mentions WHERE personId = :personId")
    fun sourceCount(personId: Long): Int

    @Query("SELECT COALESCE(SUM(count), 0) FROM person_mentions WHERE personId = :personId")
    fun totalMentions(personId: Long): Int

    @Query("DELETE FROM person_mentions WHERE personId = :personId")
    fun deleteForPerson(personId: Long)
}
