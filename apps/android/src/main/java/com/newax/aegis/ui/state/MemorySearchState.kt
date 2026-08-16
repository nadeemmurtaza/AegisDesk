package com.newax.aegis.ui.state

/**
 * Memory search (routes 2.1/2.2) — the plain-Kotlin half of the search surface.
 *
 * The ranking itself lives in [com.newax.aegis.memory.EncryptedMemory.relevant]
 * (TF-IDF over every stored fact); this holder is the display half: deciding
 * when a query is worth searching, mapping a ranked fact back to the category
 * that owns it (so the hit can open that category's editor — route 2.3), and
 * computing the highlight range for the matched term (SC 1.4.1 — the match is
 * also visually emphasized, never only coloured, and the range drives an
 * `AnnotatedString` the reader can hear).
 *
 * Stateless, like the other holders: the memory content lives in the encrypted
 * store behind `MainViewModel.memory`.
 */
class MemorySearchState {

    /** The minimum query length before a search is meaningful — two chars. */
    fun isActive(query: String): Boolean = query.trim().length >= 2

    /**
     * Which category owns [fact]? Used to open route 2.3 (the category editor)
     * for a ranked hit. First match wins; null when the fact is not in the map
     * (it was forgotten between the search and the tap — the screen falls back
     * to expanding nothing).
     */
    fun categoryOf(fact: String, allCats: Map<String, List<String>>): String? =
        allCats.entries
            .firstOrNull { (_, facts) -> facts.any { it.equals(fact, ignoreCase = true) } }
            ?.key

    /**
     * The index range of the strongest query-term match inside [fact], for
     * highlighting. Longest query word wins (the most specific term), and the
     * match is case-insensitive. Null when nothing matches.
     */
    fun highlightRange(fact: String, query: String): IntRange? {
        val words = query.trim()
            .lowercase()
            .split(Regex("\\W+"))
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
        if (words.isEmpty()) return null
        val hay = fact.lowercase()
        for (w in words) {
            val idx = hay.indexOf(w)
            if (idx >= 0) return idx until (idx + w.length)
        }
        return null
    }
}
