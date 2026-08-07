package com.newax.aegis.engine.dev.trace

data class ResolvedEntity(
    val text: String,
    val type: String,
    val confidence: Float,
    val sourceIndex: Int
)

data class SearchPlan(
    val strategies: List<String>,
    val indexesUsed: List<String>,
    val indexesSkipped: List<String>,
    val shortCircuitReason: String? = null
)

data class VerificationResult(
    val passed: Boolean,
    val checks: List<String>,
    val score: Float,
    val failedCheck: String? = null
)

data class LearningResult(
    val memoriesUpdated: Int,
    val patternDetected: Boolean,
    val note: String? = null
)

data class TaskTrace(
    val id: String,
    val queryText: String,
    val startMs: Long = System.currentTimeMillis(),
    var intent: String? = null,
    var intentConfidence: Float = 0f,
    var entities: List<ResolvedEntity> = emptyList(),
    var searchPlan: SearchPlan? = null,
    var capabilityChosen: String? = null,
    var executorChosen: String? = null,
    var confidenceScore: Float = 0f,
    var riskScore: Float = 0f,
    var verificationResult: VerificationResult? = null,
    var learningResult: LearningResult? = null,
    var candidateCount: Int = 0,
    var rankingMs: Long = 0L,
    var executionMs: Long = 0L,
    var endMs: Long? = null,
    var success: Boolean = false,
    var errorMessage: String? = null,
    val steps: MutableList<TraceStep> = mutableListOf()
) {
    val totalMs: Long get() = (endMs ?: System.currentTimeMillis()) - startMs
}

data class TraceStep(
    val stage: String,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L
)
