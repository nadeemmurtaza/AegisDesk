package com.newax.aegis.memory

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Device-only, encrypted long-term facts. Categorized for a complete User Profile. */
class EncryptedMemory(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "aegis_private_memory",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val lock = Any()

    private val CATEGORY_PREFIX = "profile_"

    private val SYSTEM_KEYS = setOf("aegis_db_passphrase")

    fun remember(category: String, fact: String) {
        if (fact.isBlank()) return
        synchronized(lock) {
            val key = "$CATEGORY_PREFIX${category.lowercase()}"
            val facts = getCategory(category).toMutableList()
            if (facts.none { it.equals(fact, true) }) {
                facts += fact.trim()
                prefs.edit().putStringSet(key, facts.toSet()).apply()
                captureProfile(category)
            }
        }
    }

    /** Remove a single fact from a category without touching the rest. */
    fun forget(category: String, fact: String) {
        if (fact.isBlank()) return
        synchronized(lock) {
            val key = "$CATEGORY_PREFIX${category.lowercase()}"
            val facts = getCategory(category).toMutableList()
            val removed = facts.removeAll { it.equals(fact.trim(), ignoreCase = true) }
            if (removed) {
                prefs.edit().putStringSet(key, facts.toSet()).apply()
                captureProfile(category)
            }
        }
    }

    fun getCategory(category: String): List<String> =
        prefs.getStringSet("$CATEGORY_PREFIX${category.lowercase()}", emptySet()).orEmpty().sorted()

    fun setCategory(category: String, facts: List<String>) = synchronized(lock) {
        prefs.edit().putStringSet("$CATEGORY_PREFIX${category.lowercase()}", facts.toSet()).apply()
        captureProfile(category)
    }

    /** Returns all categories that have at least one fact, including dynamically created ones. */
    fun getAllCategories(): Map<String, List<String>> {
        val allKeys = prefs.all.keys
        val dynamicCategories = allKeys
            .filter { it.startsWith(CATEGORY_PREFIX) }
            .map { it.removePrefix(CATEGORY_PREFIX) }
        return dynamicCategories.associateWith { getCategory(it) }.filterValues { it.isNotEmpty() }
    }

    fun forgetAll() = synchronized(lock) {
        // Journal the pre-clear categories as empty so remote devices converge
        // on the cleared state (LWW by hlc — the clear is the newest write).
        val cleared = getAllCategories().keys
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(CATEGORY_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
        cleared.forEach { captureProfile(it) }
    }

    /**
     * Export all stored data for backup.
     * Returns (strings, stringSets) — both maps with raw prefs keys.
     */
    fun exportAll(): Pair<Map<String, String>, Map<String, Set<String>>> {
        val strings    = mutableMapOf<String, String>()
        val stringSets = mutableMapOf<String, Set<String>>()
        prefs.all.forEach { (k, v) ->
            if (SYSTEM_KEYS.contains(k)) return@forEach
            when (v) {
                is String -> strings[k] = v
                is Set<*> -> stringSets[k] = v.filterIsInstance<String>().toSet()
                else      -> {}
            }
        }
        return strings to stringSets
    }

    fun importAll(strings: Map<String, String>, stringSets: Map<String, Set<String>>) = synchronized(lock) {
        val editor = prefs.edit()
        prefs.all.keys.filter { !SYSTEM_KEYS.contains(it) }.forEach { editor.remove(it) }
        strings.filterKeys { !SYSTEM_KEYS.contains(it) }.forEach { (k, v) -> editor.putString(k, v) }
        stringSets.filterKeys { !SYSTEM_KEYS.contains(it) }.forEach { (k, v) -> editor.putStringSet(k, v) }
        editor.apply()
    }

    /**
     * Apply a state received from a paired device (sync materialization)
     * WITHOUT journaling it — the journal already carries this state; a
     * re-capture would bounce the entry back and forth (newer HLC each hop).
     */
    fun applyRemoteCategory(category: String, facts: List<String>) = synchronized(lock) {
        prefs.edit().putStringSet("$CATEGORY_PREFIX${category.lowercase()}", facts.toSet()).apply()
    }

    /**
     * Journal the current state of one category for cross-device sync
     * (best-effort — sync must never break the memory write).
     */
    private fun captureProfile(category: String) {
        try {
            com.newax.aegis.SyncRuntime.captureMemoryProfile(category, getCategory(category))
        } catch (_: Exception) {
            // Sync not initialized / DB not ready — memory still saved locally.
        }
    }

    fun storeRaw(key: String, value: String) = synchronized(lock) {
        prefs.edit().putString(key, value).apply()
    }

    fun getRaw(key: String): String? = prefs.getString(key, null)

    /** BM25-inspired relevance: scores each fact by query-word overlap, weighted by rarity. */
    fun relevant(query: String, limit: Int = 6): List<String> {
        val queryWords = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val allFacts = getAllCategories().values.flatten()
        if (allFacts.isEmpty() || queryWords.isEmpty()) return emptyList()

        val n = allFacts.size.toDouble()
        val df = mutableMapOf<String, Int>()
        for (fact in allFacts) {
            for (word in fact.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()) {
                df[word] = df.getOrDefault(word, 0) + 1
            }
        }

        return allFacts.map { fact ->
            val factWords = fact.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
            val score = queryWords.sumOf { qw ->
                val tf = factWords.count { it == qw }.toDouble()
                val idf = if ((df[qw] ?: 0) > 0) kotlin.math.ln(n / (df[qw]!!.toDouble())) else 0.0
                tf * idf
            }
            fact to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
