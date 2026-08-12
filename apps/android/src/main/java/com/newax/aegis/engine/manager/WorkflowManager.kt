package com.newax.aegis.engine.manager

import com.newax.aegis.engine.bus.NewaxEvent
import com.newax.aegis.engine.bus.NewaxEventBus
import com.newax.aegis.engine.intelligence.SkillRegistry
import com.newax.aegis.engine.registry.WorkflowDefinition
import com.newax.aegis.engine.registry.WorkflowRegistry
import com.newax.aegis.engine.registry.WorkflowRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WorkflowManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun execute(
        workflowId: String,
        initialInputs: Map<String, Any> = emptyMap()
    ): Result<Map<String, Any>> {
        val workflow = WorkflowRegistry.get(workflowId)
            ?: return Result.failure(IllegalArgumentException("Workflow not found: $workflowId"))
        return execute(workflow, initialInputs)
    }

    suspend fun execute(
        workflow: WorkflowDefinition,
        initialInputs: Map<String, Any> = emptyMap()
    ): Result<Map<String, Any>> {
        val run = WorkflowRegistry.startRun(workflow.id)
        NewaxEventBus.emit(NewaxEvent.WorkflowStarted(run.id, workflow.name))

        val context = initialInputs.toMutableMap()
        var currentRun = run

        for ((idx, step) in workflow.steps.withIndex()) {
            currentRun = WorkflowRegistry.updateRun(run.id) {
                copy(currentStepIndex = idx)
            } ?: run

            val condition = step.condition
            if (condition != null && !evaluateCondition(condition, context)) {
                continue
            }

            val stepInputs = step.inputs.mapValues { (_, v) ->
                if (v is String && v.startsWith("\$")) context[v.substring(1)] ?: v else v
            } + initialInputs

            val result = SkillRegistry.invoke(step.skillId, stepInputs)
            if (result.isFailure) {
                WorkflowRegistry.updateRun(run.id) {
                    copy(status = "failed", completedMs = System.currentTimeMillis(),
                        error = "Step '${step.name}' failed: ${result.exceptionOrNull()?.message}")
                }
                NewaxEventBus.emit(NewaxEvent.WorkflowCompleted(run.id, false))
                return Result.failure(result.exceptionOrNull() ?: RuntimeException("Step ${step.name} failed"))
            }

            val stepResult = result.getOrDefault(emptyMap())
            if (step.outputKey != null) {
                context[step.outputKey] = stepResult
            }
            stepResult.forEach { (k, v) -> context[k] = v }
        }

        WorkflowRegistry.updateRun(run.id) {
            copy(status = "completed", completedMs = System.currentTimeMillis(),
                stepResults = context.mapValues { (_, v) -> v })
        }
        NewaxEventBus.emit(NewaxEvent.WorkflowCompleted(run.id, true))
        return Result.success(context)
    }

    fun executeAsync(workflowId: String, inputs: Map<String, Any> = emptyMap()) {
        scope.launch { execute(workflowId, inputs) }
    }

    private fun evaluateCondition(condition: String, context: Map<String, Any>): Boolean {
        return when {
            condition.startsWith("exists:") -> context.containsKey(condition.substring(7))
            condition.startsWith("!exists:") -> !context.containsKey(condition.substring(8))
            condition.startsWith("equals:") -> {
                val parts = condition.substring(7).split("=", limit = 2)
                parts.size == 2 && context[parts[0]]?.toString() == parts[1]
            }
            else -> true
        }
    }

    fun activeWorkflows(): List<WorkflowRun> = WorkflowRegistry.activeRuns()
}
