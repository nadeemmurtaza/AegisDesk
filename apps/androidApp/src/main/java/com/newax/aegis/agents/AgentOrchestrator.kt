package com.newax.aegis.agents

import com.newax.aegis.memory.AgentMemory
import com.newax.aegis.db.entity.EpisodeOutcome

/**
 * The orchestrator (docs/AGENTS_DESIGN.md) — turns a user request into an
 * agent assembly:
 *
 *  - [planFor] splits the request into steps ([AgentRouter]) and routes each
 *    step independently → per-step dominant agent (+ supporters),
 *  - [assemble] makes the agents actually communicate: every step is recorded
 *    as an episode, and when control passes from one dominant agent to a
 *    DIFFERENT one, a handoff is written through the L3 shared-write layer
 *    (AgentMemory.createHandoff) — agent A assigns the next step to agent B
 *    directly, and the handoff appears in B's inbox,
 *  - [contextFor] renders the active-agent block injected into the model
 *    prompt, so the LLM reasons with the dominant agent's role in scope.
 *
 * The agents share ONE model (repo invariant: one loaded model serves many
 * agents via prompt profiles) — what the registry manages is their lifecycle,
 * routing, orchestration, and memory; the per-agent "brain" is the role
 * context block + the memory layers they share.
 */
object AgentOrchestrator {

    data class OrchestratedStep(
        val text: String,
        val dominant: com.newax.aegis.db.entity.AgentEntity?,
        val supporters: List<com.newax.aegis.db.entity.AgentEntity>
    )

    data class OrchestrationPlan(
        val steps: List<OrchestratedStep>,
        val anyAgentActive: Boolean
    )

    /** Route the request (single or multi-step) against the enabled agents. */
    fun planFor(input: String): OrchestrationPlan {
        val steps = AgentRouter.planFor(input).map { step ->
            OrchestratedStep(step.text, step.route?.dominant, step.route?.supporters ?: emptyList())
        }
        return OrchestrationPlan(steps, steps.any { it.dominant != null })
    }

    /**
     * The agent-to-agent communication pass: record each routed step as an
     * episode (the mesh audit trail) and chain handoffs between consecutive
     * steps whose dominant agents differ — the previous dominant assigns the
     * next step to the new one directly (L3 shared write). Returns the number
     * of handoffs written. Best-effort; never throws.
     */
    fun assemble(plan: OrchestrationPlan): Int {
        if (!plan.anyAgentActive) return 0
        var handoffs = 0
        plan.steps.forEachIndexed { index, step ->
            val dominant = step.dominant ?: return@forEachIndexed
            runCatching {
                AgentMemory.recordEpisode(
                    agentId = "agent:${dominant.agentId}",
                    category = "orchestration",
                    summary = step.text.take(200),
                    outcome = EpisodeOutcome.OBSERVATION,
                    contextRef = "step:${index + 1}"
                )
            }
            val previous = plan.steps.getOrNull(index - 1)?.dominant
            if (previous != null && previous.agentId != dominant.agentId) {
                runCatching {
                    AgentMemory.createHandoff(
                        fromAgent = "agent:${previous.agentId}",
                        toAgent = "agent:${dominant.agentId}",
                        task = "Continue step ${index + 1}",
                        summary = step.text.take(300),
                        refId = "step:${index + 1}"
                    )
                    handoffs++
                }
            }
        }
        return handoffs
    }

    /** The active-agent block injected into the model prompt. */
    fun contextFor(plan: OrchestrationPlan): String {
        if (!plan.anyAgentActive) return ""
        return buildString {
            append("Active agents:\n")
            plan.steps.forEachIndexed { index, step ->
                val d = step.dominant ?: return@forEachIndexed
                append("- Step ${index + 1}: ${d.name} (${d.category}) — ${d.description}")
                if (step.supporters.isNotEmpty()) {
                    append("  Supports: ${step.supporters.joinToString(", ") { it.name }}")
                }
                append('\n')
            }
            append("Respond as the dominant agent(s) for each step; if a step needs an action, propose it as the dominant agent would.\n")
        }
    }
}
