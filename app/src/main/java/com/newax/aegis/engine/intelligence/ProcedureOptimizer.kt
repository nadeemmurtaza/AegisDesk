package com.newax.aegis.engine.intelligence

import java.util.concurrent.ConcurrentHashMap

data class ExecutionRecord(
    val procedureId: Long,
    val stepCount: Int,
    val successfulSteps: List<String>,
    val failedStep: String?,
    val totalMs: Long,
    val contextPackage: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class OptimizationSuggestion(
    val procedureId: Long,
    val type: OptimizationType,
    val description: String,
    val estimatedSpeedup: Float,
    val confidence: Float,
    val data: Map<String, Any> = emptyMap()
)

enum class OptimizationType {
    SKIP_REDUNDANT_WAITS,
    REORDER_STEPS,
    CACHE_LOOKUP,
    MERGE_STEPS,
    EARLY_EXIT,
    PARALLEL_CAPABLE
}

object ProcedureOptimizer {

    private const val MIN_RUNS_FOR_OPTIMIZATION = 5
    private const val SLOW_STEP_THRESHOLD_MS = 2000L
    private const val FAST_PATH_THRESHOLD_MS = 500L

    private val executionHistory = ConcurrentHashMap<Long, MutableList<ExecutionRecord>>()
    private val suggestions = ConcurrentHashMap<Long, List<OptimizationSuggestion>>()

    fun recordExecution(record: ExecutionRecord) {
        executionHistory.getOrPut(record.procedureId) { mutableListOf() }.add(record)
        val history = executionHistory[record.procedureId]!!
        if (history.size >= MIN_RUNS_FOR_OPTIMIZATION) {
            suggestions[record.procedureId] = analyze(record.procedureId, history)
        }
    }

    fun getSuggestions(procedureId: Long): List<OptimizationSuggestion> =
        suggestions[procedureId] ?: emptyList()

    fun getHighConfidenceSuggestions(threshold: Float = 0.75f): Map<Long, List<OptimizationSuggestion>> =
        suggestions.mapValues { (_, v) -> v.filter { it.confidence >= threshold } }
            .filter { it.value.isNotEmpty() }

    fun averageDuration(procedureId: Long): Long {
        val h = executionHistory[procedureId] ?: return 0L
        return if (h.isEmpty()) 0L else h.sumOf { it.totalMs } / h.size
    }

    fun successRate(procedureId: Long): Float {
        val h = executionHistory[procedureId] ?: return 1f
        if (h.isEmpty()) return 1f
        return h.count { it.failedStep == null }.toFloat() / h.size
    }

    fun commonFailureStep(procedureId: Long): String? {
        val h = executionHistory[procedureId] ?: return null
        return h.mapNotNull { it.failedStep }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }?.key
    }

    private fun analyze(procedureId: Long, history: List<ExecutionRecord>): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()

        val avgMs = history.sumOf { it.totalMs } / history.size
        if (avgMs > SLOW_STEP_THRESHOLD_MS) {
            suggestions.add(
                OptimizationSuggestion(
                    procedureId = procedureId,
                    type = OptimizationType.CACHE_LOOKUP,
                    description = "Average duration ${avgMs}ms — caching initial lookup may help",
                    estimatedSpeedup = 1.4f,
                    confidence = 0.7f,
                    data = mapOf("avgMs" to avgMs)
                )
            )
        }

        val allSteps = history.flatMap { it.successfulSteps }
        val stepCounts = allSteps.groupingBy { it }.eachCount()
        val duplicateSteps = stepCounts.filter { it.value > history.size }
        if (duplicateSteps.isNotEmpty()) {
            suggestions.add(
                OptimizationSuggestion(
                    procedureId = procedureId,
                    type = OptimizationType.SKIP_REDUNDANT_WAITS,
                    description = "Steps ${duplicateSteps.keys} appear more than once per run",
                    estimatedSpeedup = 1.2f,
                    confidence = 0.8f,
                    data = mapOf("steps" to duplicateSteps.keys.toList())
                )
            )
        }

        val successfulRuns = history.filter { it.failedStep == null }
        val fastRuns = successfulRuns.filter { it.totalMs < FAST_PATH_THRESHOLD_MS }
        if (fastRuns.size >= 3 && fastRuns.size < successfulRuns.size) {
            val fastContextPackages = fastRuns.map { it.contextPackage }.toSet()
            suggestions.add(
                OptimizationSuggestion(
                    procedureId = procedureId,
                    type = OptimizationType.EARLY_EXIT,
                    description = "Fast path found for context: $fastContextPackages",
                    estimatedSpeedup = avgMs.toFloat() / (fastRuns.sumOf { it.totalMs } / fastRuns.size),
                    confidence = 0.65f,
                    data = mapOf("fastContextPackages" to fastContextPackages.toList())
                )
            )
        }

        return suggestions
    }

    fun stats(): Map<Long, ProcedureStats> = executionHistory.mapValues { (id, history) ->
        ProcedureStats(
            procedureId = id,
            runs = history.size,
            avgMs = if (history.isEmpty()) 0L else history.sumOf { it.totalMs } / history.size,
            successRate = successRate(id),
            commonFailure = commonFailureStep(id),
            suggestionCount = suggestions[id]?.size ?: 0
        )
    }

    data class ProcedureStats(
        val procedureId: Long,
        val runs: Int,
        val avgMs: Long,
        val successRate: Float,
        val commonFailure: String?,
        val suggestionCount: Int
    )
}
