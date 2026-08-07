package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.TriggerRule

@Dao
interface TriggerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rule: TriggerRule): Long

    @Update
    fun update(rule: TriggerRule)

    @Delete
    fun delete(rule: TriggerRule)

    @Query("SELECT * FROM trigger_rules WHERE enabled = 1 ORDER BY createdMs DESC")
    fun enabledRules(): List<TriggerRule>

    @Query("SELECT * FROM trigger_rules ORDER BY createdMs DESC")
    fun allRules(): List<TriggerRule>

    @Query("SELECT * FROM trigger_rules WHERE conditionType = :type AND enabled = 1")
    fun rulesByCondition(type: String): List<TriggerRule>

    @Query("UPDATE trigger_rules SET lastFiredMs = :now WHERE id = :id")
    fun stampFired(id: Long, now: Long)

    @Query("UPDATE trigger_rules SET enabled = :enabled WHERE id = :id")
    fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM trigger_rules WHERE id = :id")
    fun deleteById(id: Long)
}
