package com.newax.aegis.engine.planner

import com.newax.aegis.engine.planner.QueryPlanner.RetrievalPath

object CandidateMerger {

    data class MergedResult(
        val summary: String,
        val topFacts: List<String>,
        val confidence: Float,
        val requiresLlm: Boolean,
        val llmContext: String
    )

    private val SOURCE_WEIGHTS = mapOf(
        RetrievalPath.CONTACT_LOOKUP  to 1.00f,
        RetrievalPath.CALENDAR        to 0.95f,
        RetrievalPath.KV_EXACT        to 0.90f,
        RetrievalPath.GRAPH_TRAVERSAL to 0.82f,
        RetrievalPath.OBJECT_STORE    to 0.80f,
        RetrievalPath.TEMPORAL_FILTER to 0.78f,
        RetrievalPath.FTS_BM25        to 0.72f,
        RetrievalPath.GRAPH_MULTIHOP  to 0.70f,
        RetrievalPath.VECTOR_SEMANTIC to 0.68f,
        RetrievalPath.PREFIX_TRIE     to 0.60f
    )

    fun merge(
        results: List<DeterministicResolver.ResolvedResult>,
        plan: QueryPlanner.QueryPlan
    ): MergedResult {
        if (results.isEmpty()) return MergedResult(
            summary     = "",
            topFacts    = emptyList(),
            confidence  = 0f,
            requiresLlm = true,
            llmContext  = ""
        )

        // Score = result.confidence × source weight
        val scored = results.map { r ->
            val w = SOURCE_WEIGHTS[r.source] ?: 0.60f
            r to r.confidence * w
        }.sortedByDescending { it.second }

        // Dedup by normalized prefix (first 80 chars lowercased)
        val seen  = mutableSetOf<String>()
        val dedup = scored.filter { (r, _) ->
            val key = r.content.lowercase().take(80)
            seen.add(key)
        }

        val topFacts   = dedup.take(5).map { it.first.content }
        val maxScore   = dedup.firstOrNull()?.second ?: 0f
        val needsLlm   = plan.requiresLlm || maxScore < 0.65f

        val summary = if (topFacts.size == 1) {
            topFacts[0]
        } else {
            topFacts.take(3).joinToString("\n") { "• $it" }
        }

        val llmCtx = if (needsLlm && topFacts.isNotEmpty()) {
            "Context:\n" + topFacts.joinToString("\n") { "- $it" }
        } else ""

        return MergedResult(
            summary     = summary,
            topFacts    = topFacts,
            confidence  = maxScore,
            requiresLlm = needsLlm,
            llmContext  = llmCtx
        )
    }
}
