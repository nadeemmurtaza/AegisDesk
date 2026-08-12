package com.newax.aegis.agents

import com.newax.aegis.db.entity.AgentEntity

/**
 * Deterministic, offline-first routing (docs/AGENTS_DESIGN.md) — the signal
 * that decides which agent dominates. No model involved: user input is scored
 * against each enabled agent's routing vocabulary (manifest keywords + name +
 * category) with word-boundary matching, the highest scorer is the dominant
 * agent, and any other agent scoring at least half the dominant's score joins
 * as a supporter (the multi-agent assembly).
 *
 * Multi-step tasks are split on the "then"-family connectors (the same
 * convention the chat already uses), and each step is routed independently —
 * so step 1 (planning) is dominated by the Planning Agent and step 2 (coding)
 * by the Coding Agent.
 */
object AgentRouter {

    /** The winner + helpers for one input. [dominant] is null when nothing scores. */
    data class Route(
        val dominant: AgentEntity,
        val supporters: List<AgentEntity>,
        val score: Int
    )

    /** One step of a (possibly multi-step) request, with its own route. */
    data class StepPlan(val text: String, val route: Route?)

    /** Split a request on "then"-family connectors — mirrors the chat's step split. */
    fun splitSteps(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split(
            Regex("\\s+(?:and then|after that|then|next)\\s+", RegexOption.IGNORE_CASE)
        ).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(trimmed) else parts
    }

    /**
     * Score one input against one agent. Word-boundary keyword hits count 1
     * each; a mention of the agent's own name or category is a strong signal.
     */
    private fun score(input: String, agent: AgentEntity): Int {
        val lower = input.lowercase()
        var score = 0
        agent.keywords.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { kw ->
            if (Regex("\\b" + Regex.escape(kw.lowercase()) + "\\b").containsMatchIn(lower)) score++
        }
        if (Regex("\\b" + Regex.escape(agent.name.lowercase()) + "\\b").containsMatchIn(lower)) score += 3
        if (Regex("\\b" + Regex.escape(agent.category.lowercase()) + "\\b").containsMatchIn(lower)) score += 2
        return score
    }

    /** Route one input against the enabled agent set; null when nothing scores. */
    fun route(input: String, agents: List<AgentEntity>): Route? {
        if (input.isBlank()) return null
        val scored = agents.map { it to score(input, it) }.filter { it.second > 0 }
        if (scored.isEmpty()) return null
        val dominant = scored.maxByOrNull { it.second } ?: return null
        val threshold = (dominant.second + 1) / 2
        val supporters = scored
            .filter { it.first.agentId != dominant.first.agentId && it.second >= threshold }
            .map { it.first }
        return Route(dominant.first, supporters, dominant.second)
    }

    /** Route every step of a request — the per-step dominance plan. */
    fun plan(input: String, agents: List<AgentEntity>): List<StepPlan> =
        splitSteps(input).map { step -> StepPlan(step, route(step, agents)) }

    /** Convenience — plan against the enabled agents from the registry. */
    fun planFor(input: String): List<StepPlan> = plan(input, AgentRegistry.enabledAgents())
}
