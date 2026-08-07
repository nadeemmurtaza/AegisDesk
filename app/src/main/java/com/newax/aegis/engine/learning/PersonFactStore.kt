package com.newax.aegis.engine.learning

import com.newax.aegis.memory.EncryptedMemory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-person fact store and cross-source importance tracker.
 *
 * Every contact/person that appears across scan sources gets:
 *  - A fact list (facts extracted about them, deduplicated)
 *  - A mention map (how many times seen in each ScanSource)
 *  - An importance score (0–1) based on cross-source presence + frequency
 *
 * When importance crosses a threshold, needsProfileBuild() returns true,
 * signalling BackgroundLearner to call ContactsManager.buildPersonProfile().
 *
 * All data lives inside EncryptedMemory (storeRaw/getRaw) with key prefixes:
 *   pf_<nameSlug>    → JSON array of PersonFact
 *   pm_<nameSlug>    → JSON object: { sourceName: count, _total: N, _last_seen: ms }
 *   pf_index         → JSON array of tracked names
 *   pm_<nameSlug>_built → "1" when profile was built, prevents re-triggering
 */
object PersonFactStore {

    // Appear in this many distinct sources → auto-trigger profile build
    private const val CROSS_SOURCE_THRESHOLD = 2
    // OR total mentions across all sources → auto-trigger
    private const val TOTAL_MENTION_THRESHOLD = 12
    // Max facts kept per person (oldest trimmed first)
    private const val MAX_FACTS_PER_PERSON = 200

    data class PersonFact(
        val name: String,
        val fact: String,
        val category: String,
        val confidence: Float,
        val source: String,
        val timestampMs: Long
    )

    data class PersonImportance(
        val name: String,
        val score: Float,
        val sourceCount: Int,
        val totalMentions: Int,
        val lastSeenMs: Long
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /** Store a fact linked to a named person. Deduplicates by fact text. */
    fun addFact(memory: EncryptedMemory, name: String, draft: LearningDraft) {
        val facts = loadFacts(memory, name).toMutableList()
        val already = facts.any { trigramJaccard(it.fact.lowercase(), draft.fact.lowercase()) > 0.80f }
        if (!already) {
            facts += PersonFact(
                name        = name,
                fact        = draft.fact,
                category    = draft.category,
                confidence  = draft.confidence,
                source      = draft.source,
                timestampMs = draft.timestampMs
            )
            val trimmed = if (facts.size > MAX_FACTS_PER_PERSON) facts.takeLast(MAX_FACTS_PER_PERSON) else facts
            memory.storeRaw(factKey(name), serializeFacts(trimmed))
            addToIndex(memory, name)
        }
    }

    /** All facts stored about a person, newest first. */
    fun factsFor(memory: EncryptedMemory, name: String): List<PersonFact> =
        loadFacts(memory, name).sortedByDescending { it.timestampMs }

    /**
     * Record that this person was encountered in a scan source.
     * Called by BackgroundLearner every time a person's name appears.
     */
    fun recordMention(memory: EncryptedMemory, name: String, source: String) {
        val key = mentionKey(name)
        val obj = runCatching { JSONObject(memory.getRaw(key) ?: "{}") }.getOrDefault(JSONObject())
        obj.put(source, obj.optInt(source, 0) + 1)
        val total = obj.keys().asSequence().filter { !it.startsWith("_") }.sumOf { obj.optInt(it, 0) }
        obj.put("_total", total)
        obj.put("_last_seen", System.currentTimeMillis())
        memory.storeRaw(key, obj.toString())
        addToIndex(memory, name)
    }

    /**
     * Composite importance score 0–1.
     * 60% from cross-source diversity, 40% from raw mention frequency.
     */
    fun getImportanceScore(memory: EncryptedMemory, name: String): Float {
        val obj = mentionObj(memory, name) ?: return 0f
        val sources      = obj.keys().asSequence().count { !it.startsWith("_") }.toFloat()
        val totalMentions = obj.optInt("_total", 0).toFloat()
        val sourceFactor  = (sources / ScanSource.entries.size).coerceIn(0f, 1f)
        val mentionFactor = (totalMentions / 50f).coerceIn(0f, 1f)
        return sourceFactor * 0.6f + mentionFactor * 0.4f
    }

    /** Top N people by importance score. */
    fun getTopPeople(memory: EncryptedMemory, limit: Int = 20): List<PersonImportance> {
        return loadIndex(memory).map { name ->
            val obj           = mentionObj(memory, name)
            val sources       = obj?.keys()?.asSequence()?.count { !it.startsWith("_") } ?: 0
            val totalMentions = obj?.optInt("_total", 0) ?: 0
            val lastSeen      = obj?.optLong("_last_seen", 0L) ?: 0L
            PersonImportance(
                name          = name,
                score         = getImportanceScore(memory, name),
                sourceCount   = sources,
                totalMentions = totalMentions,
                lastSeenMs    = lastSeen
            )
        }.sortedByDescending { it.score }.take(limit)
    }

    /** True if person has crossed the threshold and hasn't had a profile built yet. */
    fun needsProfileBuild(memory: EncryptedMemory, name: String): Boolean {
        if (memory.getRaw("${mentionKey(name)}_built") == "1") return false
        val obj           = mentionObj(memory, name) ?: return false
        val sources       = obj.keys().asSequence().count { !it.startsWith("_") }
        val totalMentions = obj.optInt("_total", 0)
        return sources >= CROSS_SOURCE_THRESHOLD || totalMentions >= TOTAL_MENTION_THRESHOLD
    }

    fun markProfileBuilt(memory: EncryptedMemory, name: String) {
        memory.storeRaw("${mentionKey(name)}_built", "1")
    }

    /** Names that crossed the threshold and still need a profile built. */
    fun getPeopleNeedingProfileBuild(memory: EncryptedMemory): List<String> =
        loadIndex(memory).filter { needsProfileBuild(memory, it) }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun factKey(name: String)    = "pf_${slug(name)}"
    private fun mentionKey(name: String) = "pm_${slug(name)}"
    private fun slug(name: String)       = name.lowercase().replace(Regex("\\s+"), "_").take(30)

    private fun mentionObj(memory: EncryptedMemory, name: String): JSONObject? =
        runCatching { JSONObject(memory.getRaw(mentionKey(name)) ?: return null) }.getOrNull()

    private fun addToIndex(memory: EncryptedMemory, name: String) {
        val raw   = memory.getRaw("pf_index") ?: "[]"
        val names = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
        }.getOrDefault(mutableSetOf())
        if (names.add(name)) memory.storeRaw("pf_index", JSONArray(names.toList()).toString())
    }

    private fun loadIndex(memory: EncryptedMemory): List<String> =
        runCatching {
            val arr = JSONArray(memory.getRaw("pf_index") ?: return emptyList())
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())

    private fun loadFacts(memory: EncryptedMemory, name: String): List<PersonFact> =
        runCatching {
            val arr = JSONArray(memory.getRaw(factKey(name)) ?: return emptyList())
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    PersonFact(
                        name        = o.optString("name", name),
                        fact        = o.getString("fact"),
                        category    = o.optString("category", "personal"),
                        confidence  = o.optDouble("confidence", 0.7).toFloat(),
                        source      = o.optString("source", ""),
                        timestampMs = o.optLong("ts", 0L)
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())

    private fun serializeFacts(facts: List<PersonFact>): String {
        val arr = JSONArray()
        facts.forEach { f ->
            arr.put(JSONObject().apply {
                put("name",       f.name)
                put("fact",       f.fact)
                put("category",   f.category)
                put("confidence", f.confidence.toDouble())
                put("source",     f.source)
                put("ts",         f.timestampMs)
            })
        }
        return arr.toString()
    }

    private fun trigramJaccard(a: String, b: String): Float {
        if (a.length < 3 || b.length < 3) return 0f
        val ta = trigrams(a); val tb = trigrams(b)
        val inter = ta.intersect(tb).size
        val union = (ta + tb).size
        return if (union == 0) 0f else inter.toFloat() / union
    }

    private fun trigrams(s: String): Set<String> =
        (0..(s.length - 3)).map { s.substring(it, it + 3) }.toSet()
}
