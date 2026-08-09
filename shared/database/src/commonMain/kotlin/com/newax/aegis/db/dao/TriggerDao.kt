package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.TriggerRule

@Dao
interface TriggerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: TriggerRule): Long

    @Update
    suspend fun update(rule: TriggerRule)

    @Delete
    suspend fun delete(rule: TriggerRule)

    @Query("SELECT * FROM trigger_rules WHERE enabled = 1 ORDER BY createdMs DESC")
    suspend fun enabledRules(): List<TriggerRule>

    @Query("SELECT * FROM trigger_rules ORDER BY createdMs DESC")
    suspend fun allRules(): List<TriggerRule>

    @Query("SELECT * FROM trigger_rules WHERE conditionType = :type AND enabled = 1")
    suspend fun rulesByCondition(type: String): List<TriggerRule>

    @Query("UPDATE trigger_rules SET lastFiredMs = :now WHERE id = :id")
    suspend fun stampFired(id: Long, now: Long): Int

    @Query("UPDATE trigger_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean): Int

    @Query("DELETE FROM trigger_rules WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
