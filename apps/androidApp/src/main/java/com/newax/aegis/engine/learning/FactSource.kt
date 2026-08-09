package com.newax.aegis.engine.learning

enum class FactType {
    USER_DECLARED,
    VERIFIED_FACT,
    OBSERVED_PATTERN,
    MODEL_INFERENCE
}

data class SourcedFact(
    val content: String,
    val type: FactType,
    val confidence: Float,
    val evidence: String = "",
    val evidenceCount: Int = 0,
    val createdMs: Long = System.currentTimeMillis(),
    val lastObservedMs: Long = System.currentTimeMillis()
) {
    fun isReliable(): Boolean = when (type) {
        FactType.USER_DECLARED  -> true
        FactType.VERIFIED_FACT  -> confidence >= 0.8f
        FactType.OBSERVED_PATTERN -> confidence >= 0.7f && evidenceCount >= 3
        FactType.MODEL_INFERENCE  -> confidence >= 0.85f && evidenceCount >= 5
    }

    fun authorityLevel(): Int = when (type) {
        FactType.USER_DECLARED    -> 4
        FactType.VERIFIED_FACT    -> 3
        FactType.OBSERVED_PATTERN -> 2
        FactType.MODEL_INFERENCE  -> 1
    }
}
