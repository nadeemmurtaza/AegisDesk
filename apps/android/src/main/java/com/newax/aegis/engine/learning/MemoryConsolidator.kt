package com.newax.aegis.engine.learning

import com.newax.aegis.memory.EncryptedMemory
import java.util.Calendar
import java.util.Locale

/**
 * Consolidates an incoming approved draft against existing memory.
 *
 * Three checks, in order:
 *  1. Duplicate detection (trigram Jaccard ≥ 0.72) → skip
 *  2. Contradiction detection (same subject + predicate, different object) → replace old
 *  3. Everything else → store as new, tagged with current month/year
 *
 * The consolidation runs on APPROVE, not on scan — so the user's decision is
 * still respected; consolidation just prevents stale or redundant data accumulating.
 */
object MemoryConsolidator {

    // Jaccard threshold: facts this similar are treated as duplicates
    private const val DUPLICATE_THRESHOLD   = 0.72f
    // Jaccard threshold: facts this similar warrant a contradiction check
    private const val SIMILAR_THRESHOLD     = 0.42f

    enum class Action {
        STORE_NEW,           // novel fact — store it
        SKIP_DUPLICATE,      // already in memory — don't re-store
        REPLACE_EXISTING,    // contradiction found — remove old, store updated
        PRESENT_CONFLICT     // ambiguous conflict — store both, warn user
    }

    data class ConsolidationResult(
        val action: Action,
        val conflictingFact: String? = null,   // the existing fact that conflicts
        val resolvedFact: String? = null       // the fact string to actually store
    )

    /**
     * Process an approved draft and return what to do with it.
     * The caller is responsible for acting on the result (store / forget / warn).
     */
    fun processApproval(memory: EncryptedMemory, draft: LearningDraft): ConsolidationResult {
        val incomingLower = draft.fact.lowercase()

        // Check category-specific facts first (most likely matches)
        val categoryFacts = memory.getCategory(draft.category)
        for (existing in categoryFacts) {
            val result = compare(existing, incomingLower, draft.fact)
            if (result != null) return result
        }

        // Cross-category check — same fact sometimes stored under different categories
        val allFacts = memory.getAllCategories().values.flatten()
        for (existing in allFacts) {
            if (existing in categoryFacts) continue   // already checked
            val result = compare(existing, incomingLower, draft.fact)
            if (result != null) return result
        }

        return ConsolidationResult(Action.STORE_NEW, resolvedFact = tagWithDate(draft.fact))
    }

    /** Find the most similar fact in memory above the similarity threshold. */
    fun findSimilarInMemory(memory: EncryptedMemory, fact: String): Pair<String, Float>? {
        val lower = fact.lowercase()
        return memory.getAllCategories().values.flatten()
            .map { it to trigramJaccard(lower, it.lowercase()) }
            .filter { it.second >= SIMILAR_THRESHOLD }
            .maxByOrNull { it.second }
    }

    /**
     * Detect if two facts directly contradict each other.
     * Matches structured predicates: "works at", "lives in", "born in", etc.
     * Returns true only when subject matches but object differs.
     */
    fun detectContradiction(existing: String, incoming: String): Boolean {
        val predicates = listOf(
            "works? (?:at|for)",
            "lives? in",
            "studied? at",
            "married to",
            "moved? to",
            "born in",
            "lives? at",
            "based in",
            "phone (?:number )?(?:is|:)",
            "email (?:is|:)",
            "address (?:is|:)"
        )
        for (predicate in predicates) {
            val re  = Regex("(.{2,30}) $predicate (.{2,50})", RegexOption.IGNORE_CASE)
            val m1  = re.find(existing) ?: continue
            val m2  = re.find(incoming) ?: continue
            val s1  = m1.groupValues[1].trim().lowercase()
            val s2  = m2.groupValues[1].trim().lowercase()
            val o1  = m1.groupValues[2].trim().lowercase()
            val o2  = m2.groupValues[2].trim().lowercase()
            // Same subject, meaningfully different object → contradiction
            if (trigramJaccard(s1, s2) > 0.60f && trigramJaccard(o1, o2) < 0.40f) return true
        }
        return false
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun compare(existing: String, incomingLower: String, incomingRaw: String): ConsolidationResult? {
        val existingLower = existing.lowercase()
        val sim = trigramJaccard(incomingLower, existingLower)

        return when {
            sim >= DUPLICATE_THRESHOLD -> ConsolidationResult(
                Action.SKIP_DUPLICATE,
                conflictingFact = existing
            )
            sim >= SIMILAR_THRESHOLD && detectContradiction(existing, incomingRaw) -> ConsolidationResult(
                Action.REPLACE_EXISTING,
                conflictingFact  = existing,
                resolvedFact     = tagWithDate(incomingRaw)
            )
            sim >= SIMILAR_THRESHOLD + 0.05f && !detectContradiction(existing, incomingRaw) -> {
                // Similar but not clearly a contradiction — both might be valid
                ConsolidationResult(
                    Action.PRESENT_CONFLICT,
                    conflictingFact = existing,
                    resolvedFact    = tagWithDate(incomingRaw)
                )
            }
            else -> null
        }
    }

    private fun tagWithDate(fact: String): String {
        val cal   = Calendar.getInstance()
        val month = cal.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault())
        val year  = cal.get(Calendar.YEAR)
        return "$fact [$month $year]"
    }

    private fun trigramJaccard(a: String, b: String): Float {
        if (a.length < 3 || b.length < 3) return 0f
        val ta = trigrams(a)
        val tb = trigrams(b)
        val intersection = ta.intersect(tb).size
        val union = (ta + tb).size
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    private fun trigrams(s: String): Set<String> =
        (0..(s.length - 3)).map { s.substring(it, it + 3) }.toSet()
}
