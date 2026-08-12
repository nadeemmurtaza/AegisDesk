package com.newax.aegis.engine.learning

import java.util.concurrent.ConcurrentHashMap

object ConfidenceEngine {

    private const val MIN_CONFIDENCE = 0
    private const val MAX_CONFIDENCE = 100
    private const val CONFIRMATION_BOOST = 10
    private const val CONTRADICTION_PENALTY = 25
    private const val TIME_DECAY_RATE = 0.02f
    private const val SOURCE_WEIGHT_DIRECT = 1.0f
    private const val SOURCE_WEIGHT_INFERRED = 0.7f
    private const val SOURCE_WEIGHT_LLM = 0.6f
    private const val SOURCE_WEIGHT_UNKNOWN = 0.5f

    private val confirmations = ConcurrentHashMap<Long, Int>()
    private val contradictions = ConcurrentHashMap<Long, Int>()

    data class ConfidenceScore(
        val raw: Int,
        val adjusted: Int,
        val sourceWeight: Float,
        val ageDecay: Float,
        val confirmationBoost: Int,
        val contradictionPenalty: Int,
        val final: Int
    )

    fun score(
        baseConfidence: Int,
        source: String,
        ageMs: Long,
        recordId: Long? = null
    ): ConfidenceScore {
        val sourceWeight = sourceWeight(source)
        val ageDays = ageMs / (24 * 3600 * 1000f)
        val ageDecay = (1f - TIME_DECAY_RATE * ageDays / 30f).coerceAtLeast(0.5f)
        val confirmBoost = (recordId?.let { confirmations[it] ?: 0 } ?: 0) * CONFIRMATION_BOOST
        val contradictPenalty = (recordId?.let { contradictions[it] ?: 0 } ?: 0) * CONTRADICTION_PENALTY
        val adjusted = ((baseConfidence * sourceWeight * ageDecay).toInt() + confirmBoost - contradictPenalty)
            .coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)
        return ConfidenceScore(
            raw = baseConfidence,
            adjusted = (baseConfidence * sourceWeight).toInt(),
            sourceWeight = sourceWeight,
            ageDecay = ageDecay,
            confirmationBoost = confirmBoost,
            contradictionPenalty = contradictPenalty,
            final = adjusted
        )
    }

    fun confirm(recordId: Long) {
        confirmations.merge(recordId, 1, Int::plus)
    }

    fun contradict(recordId: Long) {
        contradictions.merge(recordId, 1, Int::plus)
    }

    fun mergeScores(scores: List<Int>): Int {
        if (scores.isEmpty()) return 50
        if (scores.size == 1) return scores[0]
        return scores.reduce { acc, s ->
            val prob1 = acc / 100f
            val prob2 = s / 100f
            ((prob1 + prob2 - prob1 * prob2) * 100).toInt()
        }.coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)
    }

    fun bayesianUpdate(prior: Int, likelihood: Float): Int {
        val priorP = prior / 100f
        val posterior = (priorP * likelihood) / (priorP * likelihood + (1 - priorP) * (1 - likelihood))
        return (posterior * 100).toInt().coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)
    }

    fun initialConfidence(source: String, evidenceCount: Int = 1): Int {
        val base = when {
            source == "direct_input" -> 90
            source == "contact_sync" -> 85
            source == "notification" -> 70
            source.startsWith("llm") -> 60
            source == "inferred" -> 50
            else -> 55
        }
        val boost = ((evidenceCount - 1) * 5).coerceAtMost(20)
        return (base + boost).coerceAtMost(MAX_CONFIDENCE)
    }

    private fun sourceWeight(source: String): Float = when {
        source == "direct_input" || source == "contact_sync" -> SOURCE_WEIGHT_DIRECT
        source.startsWith("llm") -> SOURCE_WEIGHT_LLM
        source == "inferred" -> SOURCE_WEIGHT_INFERRED
        else -> SOURCE_WEIGHT_UNKNOWN
    }

    fun highConfidenceThreshold() = 75
    fun lowConfidenceThreshold() = 35
    fun isHighConfidence(score: Int) = score >= highConfidenceThreshold()
    fun isLowConfidence(score: Int) = score <= lowConfidenceThreshold()
}
