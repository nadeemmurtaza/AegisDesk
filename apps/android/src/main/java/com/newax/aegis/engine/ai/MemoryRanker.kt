package com.newax.aegis.engine.ai

import com.newax.aegis.db.entity.MemoryRecord

object MemoryRanker {

    data class RankedMemory(
        val record: MemoryRecord,
        val score: Float,
        val matchType: MatchType
    )

    enum class MatchType { EXACT_SUBJECT, KEYWORD, SEMANTIC, RECENT, IMPORTANT }

    fun rank(
        records: List<MemoryRecord>,
        query: String,
        topK: Int = 10
    ): List<RankedMemory> {
        val lower = query.lowercase().trim()
        val queryTokens = tokenize(lower)

        return records
            .filter { it.validUntil == null }
            .map { record ->
                val score = scoreRecord(record, lower, queryTokens)
                score
            }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun scoreRecord(record: MemoryRecord, query: String, queryTokens: List<String>): RankedMemory {
        var score = 0f
        var matchType = MatchType.RECENT

        val contentLower = record.content.lowercase()
        val subjectLower = record.subject.lowercase()

        if (subjectLower.isNotBlank() && query.contains(subjectLower)) {
            score += 5f
            matchType = MatchType.EXACT_SUBJECT
        }

        val keywordMatches = queryTokens.count { token ->
            contentLower.contains(token) || subjectLower.contains(token)
        }
        if (keywordMatches > 0) {
            score += keywordMatches * 2f
            if (matchType != MatchType.EXACT_SUBJECT) matchType = MatchType.KEYWORD
        }

        val contentTokens = tokenize(contentLower)
        val jaccard = jaccardSimilarity(queryTokens.toSet(), contentTokens.toSet())
        score += jaccard * 3f

        val importanceFactor = record.importance / 100f
        val confidenceFactor = record.confidence / 100f
        score += importanceFactor * 2f + confidenceFactor * 1f

        val ageDays = (System.currentTimeMillis() - record.updatedAt) / (24 * 3600 * 1000f)
        val recencyScore = 1f / (1f + ageDays / 30f)
        score += recencyScore

        if (record.importance > 70) {
            score += 1.5f
            if (matchType == MatchType.RECENT) matchType = MatchType.IMPORTANT
        }

        return RankedMemory(record, score, matchType)
    }

    fun diversify(ranked: List<RankedMemory>, maxPerSubject: Int = 2): List<RankedMemory> {
        val subjectCount = mutableMapOf<String, Int>()
        return ranked.filter { rm ->
            val subject = rm.record.subject.lowercase().ifBlank { "unknown" }
            val count = subjectCount.getOrDefault(subject, 0)
            if (count < maxPerSubject) {
                subjectCount[subject] = count + 1
                true
            } else false
        }
    }

    fun scoreRelevance(record: MemoryRecord, query: String): Float {
        val tokens = tokenize(query.lowercase())
        return scoreRecord(record, query.lowercase(), tokens).score
    }

    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val intersection = (a intersect b).size
        val union = (a union b).size
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    private fun tokenize(text: String): List<String> =
        text.split(" ", ",", ".", "!", "?", ";", ":", "\n")
            .map { it.trim() }
            .filter { it.length > 2 }
}
