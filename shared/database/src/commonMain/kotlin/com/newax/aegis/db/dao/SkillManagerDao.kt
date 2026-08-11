package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.*

/**
 * Persistence for the skills management system (docs/AGENTS_DESIGN.md §skills).
 * The agent_skills join is the permission table — a row is a grant.
 */
@Dao
interface SkillManagerDao {

    // ── Skills ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkill(skill: SkillEntity)

    @Query("SELECT * FROM skills ORDER BY category ASC, name ASC")
    suspend fun allSkills(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY category ASC, name ASC")
    suspend fun enabledSkills(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE skillId = :skillId LIMIT 1")
    suspend fun skillById(skillId: String): SkillEntity?

    @Query("UPDATE skills SET enabled = :enabled WHERE skillId = :skillId")
    suspend fun setSkillEnabled(skillId: String, enabled: Boolean): Int

    @Query("DELETE FROM skills WHERE skillId = :skillId")
    suspend fun deleteSkill(skillId: String): Int

    // ── PBAC — capability bridge + tool schemas ─────────────────────────────

    @Query("SELECT skillId FROM skills WHERE capability = :capability AND enabled = 1")
    suspend fun skillsForCapability(capability: String): List<String>

    @Query("SELECT toolSchema FROM skills WHERE enabled = 1")
    suspend fun allToolSchemas(): List<String>

    // ── Permissions (agent_skills join) ─────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun grantSkill(grant: AgentSkill)

    @Query("DELETE FROM agent_skills WHERE agentId = :agentId AND skillId = :skillId")
    suspend fun revokeSkill(agentId: String, skillId: String): Int

    @Query("DELETE FROM agent_skills WHERE agentId = :agentId")
    suspend fun clearAgentSkills(agentId: String): Int

    @Query("SELECT * FROM agent_skills WHERE agentId = :agentId AND skillId = :skillId LIMIT 1")
    suspend fun grantExists(agentId: String, skillId: String): AgentSkill?

    /** The permission primitive: an agent may use a skill iff granted AND both enabled. */
    @Query(
        "SELECT COUNT(*) FROM agent_skills a JOIN skills s ON s.skillId = a.skillId " +
            "WHERE a.agentId = :agentId AND a.skillId = :skillId AND s.enabled = 1"
    )
    suspend fun canUseCount(agentId: String, skillId: String): Int

    @Query(
        "SELECT s.* FROM skills s JOIN agent_skills a ON a.skillId = s.skillId " +
            "WHERE a.agentId = :agentId AND s.enabled = 1 ORDER BY s.category ASC, s.name ASC"
    )
    suspend fun skillsForAgent(agentId: String): List<SkillEntity>

    @Query(
        "SELECT a.agentId FROM agent_skills a JOIN skills s ON s.skillId = a.skillId " +
            "WHERE a.skillId = :skillId AND s.enabled = 1"
    )
    suspend fun agentsForSkill(skillId: String): List<String>

    @Query("SELECT skillId FROM agent_skills WHERE agentId = :agentId")
    suspend fun grantedSkillIds(agentId: String): List<String>

    // ── Skill sets ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSet(set: SkillSet)

    @Query("SELECT * FROM skill_sets ORDER BY name ASC")
    suspend fun allSets(): List<SkillSet>

    @Query("DELETE FROM skill_sets WHERE setId = :setId")
    suspend fun deleteSet(setId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToSet(member: SkillSetMember)

    @Query("DELETE FROM skill_set_members WHERE setId = :setId AND skillId = :skillId")
    suspend fun removeFromSet(setId: String, skillId: String): Int

    @Query(
        "SELECT s.* FROM skills s JOIN skill_set_members m ON m.skillId = s.skillId " +
            "WHERE m.setId = :setId ORDER BY s.category ASC, s.name ASC"
    )
    suspend fun skillsInSet(setId: String): List<SkillEntity>

    @Query("SELECT skillId FROM skill_set_members WHERE setId = :setId")
    suspend fun setMemberIds(setId: String): List<String>

    // ── HITL approval ledger (skill_approvals) ──────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApproval(approval: SkillApproval)

    @Query("SELECT * FROM skill_approvals WHERE approvalId = :approvalId LIMIT 1")
    suspend fun approvalById(approvalId: String): SkillApproval?

    @Query("UPDATE skill_approvals SET status = :status, decidedAtMs = :now WHERE approvalId = :approvalId")
    suspend fun setApprovalStatus(approvalId: String, status: String, now: Long): Int

    @Query("SELECT * FROM skill_approvals WHERE status = 'PENDING' ORDER BY requestedAtMs DESC")
    suspend fun pendingApprovals(): List<SkillApproval>

    @Query("SELECT * FROM skill_approvals ORDER BY requestedAtMs DESC LIMIT :limit")
    suspend fun recentApprovals(limit: Int = 50): List<SkillApproval>

    @Query("UPDATE skill_approvals SET status = 'EXPIRED' WHERE status = 'PENDING' AND requestedAtMs < :olderThan")
    suspend fun expireOldApprovals(olderThan: Long): Int
}
