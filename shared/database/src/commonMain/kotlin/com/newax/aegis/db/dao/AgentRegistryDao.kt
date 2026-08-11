package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.AgentEntity

/** Persistence for the installed agents (docs/AGENTS_DESIGN.md). */
@Dao
interface AgentRegistryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: AgentEntity)

    @Query("SELECT * FROM agents WHERE agentId = :agentId LIMIT 1")
    suspend fun byId(agentId: String): AgentEntity?

    @Query("SELECT * FROM agents ORDER BY category ASC, name ASC")
    suspend fun all(): List<AgentEntity>

    /** Routing only ever considers enabled agents. */
    @Query("SELECT * FROM agents WHERE enabled = 1 ORDER BY category ASC, name ASC")
    suspend fun enabled(): List<AgentEntity>

    @Query("UPDATE agents SET enabled = :enabled WHERE agentId = :agentId")
    suspend fun setEnabled(agentId: String, enabled: Boolean): Int

    @Query("DELETE FROM agents WHERE agentId = :agentId")
    suspend fun delete(agentId: String): Int

    @Query("SELECT COUNT(*) FROM agents")
    suspend fun count(): Int
}
