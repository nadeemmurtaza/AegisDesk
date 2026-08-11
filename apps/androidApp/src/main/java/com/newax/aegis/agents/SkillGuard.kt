package com.newax.aegis.agents

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.SkillApproval
import com.newax.aegis.db.entity.SkillApprovalStatus
import com.newax.aegis.db.entity.SkillEntity
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * The centralized Permission Guard (docs/AGENTS_DESIGN.md §permissions) — the
 * ONE gate every skill access flows through. PBAC, not roles: it does not ask
 * "is this agent an admin?"; it asks "does THIS agent instance have the exact
 * permission needed RIGHT NOW, under these conditions?".
 *
 * Decision order (first failure wins, each with a named reason):
 *  1. skill exists + enabled,
 *  2. agent exists + enabled,
 *  3. grant exists (agent_skills join — the permission),
 *  4. capability bridge: if the skill declares a `capability`, the agent must
 *     declare it too (capabilities = what the agent knows how to do; skills =
 *     the code that does the work),
 *  5. human-in-the-loop: the request PAUSES (recorded as PENDING in the
 *     approval ledger) when any of:
 *       - the skill's manifest has `requires_approval` (high-impact skills),
 *       - the skill requires a sandbox but none is available (host-filesystem
 *         skills run ONLY sandboxed; without a sandbox they wait for a human),
 *       - the request came from UNTRUSTED ingested content (email/web/OCR)
 *         and the skill is high-risk — the indirect prompt-injection
 *         containment rule ("email says wipe the disk" never hands bash to a
 *         coding agent without a human gate),
 *  6. otherwise ALLOW.
 *
 * Execution sandboxing is a pluggable seam: [sandboxProvider] returns whether a
 * sandbox runtime (WASM/Docker) is available. This app ships none today, so
 * sandbox-required skills are demoted to human approval rather than silently
 * running unsandboxed — the safe default.
 */
object SkillGuard {

    sealed interface Decision {
        data class Allow(val skill: SkillEntity) : Decision
        data class Denied(val reason: String) : Decision
        data class ApprovalRequired(val approvalId: String, val skill: SkillEntity) : Decision
    }

    /** Pluggable sandbox availability — register a WASM/Docker provider when one exists. */
    @Volatile
    var sandboxProvider: () -> Boolean = { false }

    private val highRiskCategories = setOf("automation", "communication")

    fun isSandboxAvailable(): Boolean = sandboxProvider()

    /** The single gate — every skill request must call this. */
    fun request(
        agentId: String,
        skillId: String,
        requestContext: String = "",
        untrustedSource: Boolean = false
    ): Decision {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return Decision.Denied("database-unavailable")

        val skill = runBlocking { runCatching { db.skillManagerDao().skillById(skillId) }.getOrNull() }
            ?: return Decision.Denied("skill-not-found")
        if (!skill.enabled) return Decision.Denied("skill-disabled")

        val agent = runBlocking { runCatching { db.agentRegistryDao().byId(agentId) }.getOrNull() }
            ?: return Decision.Denied("agent-not-found")
        if (!agent.enabled) return Decision.Denied("agent-disabled")

        // 3. The grant — absence = denied.
        if (!SkillManager.canUse(agentId, skillId)) return Decision.Denied("not-granted")

        // 4. Capability bridge — what the agent knows how to do vs. what the skill does.
        if (skill.capability.isNotBlank()) {
            val capabilities = agent.capabilities.split(',').map { it.trim() }.filter { it.isNotBlank() }
            if (skill.capability !in capabilities) return Decision.Denied("capability-not-declared")
        }

        // 5. Human-in-the-loop conditions.
        val needsHuman = skill.requiresApproval ||
            (skill.sandboxRequired && !isSandboxAvailable()) ||
            (untrustedSource && (skill.sandboxRequired || skill.requiresApproval || skill.category in highRiskCategories))
        if (needsHuman) {
            val approvalId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            runBlocking {
                runCatching {
                    db.skillManagerDao().upsertApproval(
                        SkillApproval(
                            approvalId = approvalId,
                            agentId = agentId,
                            skillId = skillId,
                            requestContext = requestContext.take(500),
                            untrustedSource = untrustedSource,
                            status = SkillApprovalStatus.PENDING,
                            requestedAtMs = now
                        )
                    )
                }
            }
            return Decision.ApprovalRequired(approvalId, skill)
        }

        return Decision.Allow(skill)
    }

    /** The human Allow/Deny decision on a paused request. */
    fun decideApproval(approvalId: String, allow: Boolean) {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return
        runBlocking {
            runCatching {
                db.skillManagerDao().setApprovalStatus(
                    approvalId,
                    if (allow) SkillApprovalStatus.ALLOWED else SkillApprovalStatus.DENIED,
                    System.currentTimeMillis()
                )
            }
        }
    }

    fun pendingApprovals(): List<SkillApproval> {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking {
            runCatching {
                val dao = db.skillManagerDao()
                dao.expireOldApprovals(System.currentTimeMillis() - APPROVAL_TTL_MS)
                dao.pendingApprovals()
            }.getOrDefault(emptyList())
        }
    }

    fun recentApprovals(limit: Int = 50): List<SkillApproval> {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.skillManagerDao().recentApprovals(limit) }.getOrDefault(emptyList()) }
    }

    private const val APPROVAL_TTL_MS = 30L * 60 * 1000L
}
