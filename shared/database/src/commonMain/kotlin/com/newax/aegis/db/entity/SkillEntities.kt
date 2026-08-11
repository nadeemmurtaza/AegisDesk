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
 *  - [SkillEntity] — an executable module (web_search, execute_sql, run_shell,
 *    …), packageable from a zip (`manifest.json`), enable/disable, shared by
 *    MANY agents. A skill is the CODE that does the work — separate from an
 *    agent's CAPABILITY (what the agent knows how to do).
 *  - [AgentSkill] — the PERMISSION join: which agent may use which skill.
 *    The join row IS the grant; absence = denied. Many-to-many by design.
 *  - [SkillSet] + [SkillSetMember] — named groups of skills; a set is a
 *    convenience for granting/revoking bundles.
 *  - [SkillApproval] — the human-in-the-loop ledger: when the centralized
 *    [com.newax.aegis.agents.SkillGuard] hits a `requiresApproval` skill (or
 *    an untrusted-source request for a high-risk skill), a PENDING row is
 *    recorded and execution pauses until a human Allow/Deny decision.
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
    /** The capability this skill fulfills ("code_execution", "web_research", …). */
    val capability: String = "",
    /** JSON tool schema (OpenAI-style function schema) exposed to the runtime. */
    val toolSchema: String = "{}",
    /** Skills touching the host filesystem MUST run in a sandbox; the guard refuses them when none is available. */
    val sandboxRequired: Boolean = false,
    /** HITL: the guard pauses execution and waits for an allow/deny decision. */
    val requiresApproval: Boolean = false,
    /** Comma-separated risk notes surfaced in the approval dialog. */
    val risks: String = "",
    /**
     * "agent" (default) or "global". GLOBAL-scope SYSTEM skills
     * (skill.sys.* — mcp_stream, serialize_state, health_audit, task_control)
     * are granted to every active agent implicitly: the guard skips the
     * agent_skills whitelist and the capability bridge for them, so core
     * runtime utilities never need per-agent grant rows (zero policy
     * maintenance bloat) — while dangerous shell/files skills stay "agent"
     * scope and keep every restriction.
     */
    @ColumnInfo(defaultValue = "'agent'")
    val scope: String = "agent",
    /**
     * The per-skill Learning Specification Interface (docs/AGENTS_DESIGN.md
     * §evolution — RLAIF-E). JSON: { protocol, mistake_definition,
     * test_strategy, exploration_hint }. Each skill declares HOW it wants to
     * learn (DETERMINISTIC / CRITIC / CROSS_AGENT), what counts as a mistake,
     * and how it runs tests before sending a mutation to the user gate — the
     * kernel never forces one loop onto every tool. Backfilled with a
     * category-derived default at seed time; parsed from the `learning`
     * object of an imported skill.json manifest.
     */
    @ColumnInfo(defaultValue = "'{}'")
    val learningSpec: String = "{}",
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

/** Human-in-the-loop decision record (PBAC — the permission guard's ledger). */
@Entity(tableName = "skill_approvals")
data class SkillApproval(
    @PrimaryKey val approvalId: String,
    val agentId: String,
    val skillId: String,
    /** What the agent said it was doing (surfaced in the allow/deny dialog). */
    val requestContext: String = "",
    /** True when the request came from untrusted ingested content (prompt-injection containment). */
    val untrustedSource: Boolean = false,
    /** [SkillApprovalStatus] name. */
    val status: String = SkillApprovalStatus.PENDING,
    @ColumnInfo(defaultValue = "0")
    val requestedAtMs: Long = currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val decidedAtMs: Long = 0
)

object SkillApprovalStatus {
    const val PENDING = "PENDING"
    const val ALLOWED = "ALLOWED"
    const val DENIED = "DENIED"
    const val EXPIRED = "EXPIRED"
}
