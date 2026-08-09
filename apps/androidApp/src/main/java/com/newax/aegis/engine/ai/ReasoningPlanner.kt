package com.newax.aegis.engine.ai

import com.newax.aegis.engine.intelligence.GoalPlanner
import com.newax.aegis.engine.intelligence.SkillRegistry
import com.newax.aegis.engine.registry.IntentRegistry

data class ReasoningStep(
    val index: Int,
    val thought: String,
    val action: String?,
    val skillId: String?,
    val inputs: Map<String, Any> = emptyMap(),
    val observation: String? = null,
    val result: Any? = null
)

data class ReasoningChain(
    val query: String,
    val steps: List<ReasoningStep>,
    val finalAnswer: String?,
    val confidence: Float,
    val requiresExecution: Boolean
)

object ReasoningPlanner {

    private const val MAX_STEPS = 8

    fun plan(query: String): ReasoningChain {
        val steps = mutableListOf<ReasoningStep>()
        var stepIndex = 0

        val intent = IntentRegistry.topIntent(query)
        steps.add(ReasoningStep(
            index = stepIndex++,
            thought = "Intent identified: ${intent?.intent?.name ?: "unknown"} (${((intent?.confidence ?: 0f) * 100).toInt()}%)",
            action = null,
            skillId = null
        ))

        val toolSelection = ToolSelector.select(query)
        if (toolSelection.isNotEmpty()) {
            val best = toolSelection.first()
            steps.add(ReasoningStep(
                index = stepIndex++,
                thought = "Best tool: ${best.skillName} (${(best.confidence * 100).toInt()}% confidence)",
                action = "SELECT_TOOL",
                skillId = best.skillId,
                inputs = best.requiredInputs
            ))
        }

        val missingInputs = toolSelection.firstOrNull()?.requiredInputs
            ?.filter { (_, v) -> v.startsWith("REQUIRED:") }
            ?: emptyMap()

        if (missingInputs.isNotEmpty()) {
            steps.add(ReasoningStep(
                index = stepIndex++,
                thought = "Missing inputs: ${missingInputs.keys.joinToString(", ")} — need to extract from context or ask user",
                action = "GATHER_INPUTS",
                skillId = null,
                inputs = missingInputs
            ))
        }

        val complexQuery = isComplex(query)
        if (complexQuery && stepIndex < MAX_STEPS) {
            val plan = GoalPlanner.plan(query)
            plan.graph.tasks.take(3).forEachIndexed { i, task ->
                steps.add(ReasoningStep(
                    index = stepIndex++,
                    thought = "Sub-task ${i + 1}: ${task.description}",
                    action = "EXECUTE_SUBTASK",
                    skillId = task.skillId
                ))
            }
        }

        val requiresExecution = toolSelection.any { it.confidence >= 0.6f } && missingInputs.isEmpty()
        val finalAnswer = if (!requiresExecution) {
            generateDirectAnswer(query)
        } else null

        val confidence = if (toolSelection.isEmpty()) 0.3f
        else toolSelection.first().confidence * (1f - missingInputs.size * 0.15f)

        return ReasoningChain(
            query = query,
            steps = steps,
            finalAnswer = finalAnswer,
            confidence = confidence.coerceIn(0f, 1f),
            requiresExecution = requiresExecution
        )
    }

    suspend fun execute(chain: ReasoningChain): ReasoningChain {
        if (!chain.requiresExecution) return chain
        val executedSteps = chain.steps.toMutableList()
        for (step in chain.steps) {
            if (step.skillId != null && step.action == "SELECT_TOOL") {
                val result = SkillRegistry.invoke(step.skillId, step.inputs)
                val idx = executedSteps.indexOf(step)
                executedSteps[idx] = step.copy(
                    observation = if (result.isSuccess) "Success" else "Failed: ${result.exceptionOrNull()?.message}",
                    result = result.getOrNull()
                )
            }
        }
        return chain.copy(steps = executedSteps)
    }

    fun summarize(chain: ReasoningChain): String = buildString {
        append("Reasoning for: ${chain.query}\n")
        chain.steps.forEach { step ->
            append("${step.index + 1}. ${step.thought}")
            if (step.action != null) append(" → ${step.action}")
            if (step.observation != null) append(" [${step.observation}]")
            append("\n")
        }
        if (chain.finalAnswer != null) append("\nAnswer: ${chain.finalAnswer}")
        append("\nConfidence: ${(chain.confidence * 100).toInt()}%")
        if (chain.requiresExecution) append(" (requires execution)")
    }

    private fun isComplex(query: String): Boolean {
        val lower = query.lowercase()
        val complexIndicators = listOf("and then", "after that", "first", "then", "finally",
            "step by step", "multiple", "several", "each", "for all", "compare")
        return complexIndicators.any { lower.contains(it) } || query.split(" ").size > 12
    }

    private fun generateDirectAnswer(query: String): String? {
        val lower = query.lowercase()
        return when {
            lower.contains("what time") -> "I don't have access to the current time."
            lower.contains("who are you") || lower.contains("what are you") ->
                "I'm Aegis, your offline AI assistant."
            lower.contains("what can you do") ->
                "I can search your files, messages, and memory; automate tasks; answer questions from your personal data; and more."
            else -> null
        }
    }
}
