package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The skills management system (docs/AGENTS_DESIGN.md §skills) — aligned with
 * the agents registry:
 *
 *  - [SkillEntity] — one capability package (run_shell, open_app, …), importable
 *    from a zip (`skill.json`), enable/disable, shared by MANY agents.
 *  - [AgentSkill] — the PERMISSION join: which agent may use which skill.
 *    The join row IS the grant; absence = denied. Many-to-many by design.
 *  - [SkillSet] + [SkillSetMember] — named groups of skills; a set is a
 *    convenience for granting/revoking bundles, and an agent can hold a set's
 *    skills via membership grants.
 *
 * Device-local like the agents table (no sync columns — the mesh syncs what
 * skills produce, never the packages or grants).
 */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val skillId: String,
    val name: String,
    val description: String,
    val category: String,
    val version: String,
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    /** "builtin" or "zip:<packageName>". */
    val source: String = "builtin",
    val packageDir: String = "",
    @ColumnInfo(defaultValue = "0")
    val installedAtMs: Long = currentTimeMillis()
)

/** Permission grant: agent → skill (many-to-many). Absence = denied. */
@Entity(
    tableName = "agent_skills",
    primaryKeys = ["agentId", "skillId"],
    indices = [Index("skillId")]
)
data class AgentSkill(
    val agentId: String,
    val skillId: String,
    @ColumnInfo(defaultValue = "0")
    val grantedAtMs: Long = currentTimeMillis()
)

@Entity(tableName = "skill_sets")
data class SkillSet(
    @PrimaryKey val setId: String,
    val name: String,
    val description: String = "",
    @ColumnInfo(defaultValue = "0")
    val createdAtMs: Long = currentTimeMillis()
)

/** Membership join: set → skill. */
@Entity(
    tableName = "skill_set_members",
    primaryKeys = ["setId", "skillId"],
    indices = [Index("skillId")]
)
data class SkillSetMember(
    val setId: String,
    val skillId: String
)
