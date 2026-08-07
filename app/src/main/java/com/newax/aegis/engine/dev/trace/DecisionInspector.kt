package com.newax.aegis.engine.dev.trace

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object DecisionInspector {

    private const val MAX_TRACES = 500

    private val traceMap = ConcurrentHashMap<String, TaskTrace>()
    private val traceOrder = CopyOnWriteArrayList<String>()

    fun begin(id: String, query: String): TaskTrace {
        val trace = TaskTrace(id = id, queryText = query)
        traceMap[id] = trace
        traceOrder.add(id)
        if (traceOrder.size > MAX_TRACES) {
            val oldest = traceOrder.removeAt(0)
            traceMap.remove(oldest)
        }
        return trace
    }

    fun step(id: String, stage: String, detail: String, durationMs: Long = 0L) {
        traceMap[id]?.steps?.add(TraceStep(stage, detail, durationMs = durationMs))
    }

    fun setIntent(id: String, intent: String, confidence: Float = 0f) {
        traceMap[id]?.let { it.intent = intent; it.intentConfidence = confidence }
    }

    fun addEntity(id: String, entity: ResolvedEntity) {
        traceMap[id]?.let { trace ->
            trace.entities = trace.entities + entity
        }
    }

    fun setSearchPlan(id: String, plan: SearchPlan) {
        traceMap[id]?.searchPlan = plan
    }

    fun setCapability(id: String, capability: String) {
        traceMap[id]?.capabilityChosen = capability
    }

    fun setExecutor(id: String, executor: String) {
        traceMap[id]?.executorChosen = executor
    }

    fun setConfidence(id: String, confidence: Float, risk: Float) {
        traceMap[id]?.let { it.confidenceScore = confidence; it.riskScore = risk }
    }

    fun setCandidates(id: String, count: Int) {
        traceMap[id]?.candidateCount = count
    }

    fun setVerification(id: String, result: VerificationResult) {
        traceMap[id]?.verificationResult = result
    }

    fun setLearning(id: String, result: LearningResult) {
        traceMap[id]?.learningResult = result
    }

    fun end(id: String, success: Boolean, error: String? = null) {
        traceMap[id]?.let {
            it.endMs = System.currentTimeMillis()
            it.success = success
            it.errorMessage = error
        }
    }

    fun get(id: String): TaskTrace? = traceMap[id]

    fun recent(n: Int = 20): List<TaskTrace> =
        traceOrder.takeLast(n).reversed().mapNotNull { traceMap[it] }

    fun failed(n: Int = 20): List<TaskTrace> =
        recent(MAX_TRACES).filter { !it.success }.take(n)

    fun slow(thresholdMs: Long = 2000L, n: Int = 10): List<TaskTrace> =
        recent(MAX_TRACES).filter { it.totalMs >= thresholdMs }
            .sortedByDescending { it.totalMs }.take(n)

    fun clear() {
        traceMap.clear()
        traceOrder.clear()
    }

    fun summary(): String = buildString {
        val all = recent(MAX_TRACES)
        append("DecisionInspector: ${all.size} traces\n")
        val success = all.count { it.success }
        val fail = all.count { !it.success && it.endMs != null }
        append("  success=$success  fail=$fail  avg=${if (all.isEmpty()) 0L else all.sumOf { it.totalMs } / all.size}ms\n")
        all.take(5).forEach { t ->
            append("  [${t.id}] ${t.queryText.take(40)} → ${t.intent ?: "?"} (${t.totalMs}ms, ok=${t.success})\n")
        }
    }
}
