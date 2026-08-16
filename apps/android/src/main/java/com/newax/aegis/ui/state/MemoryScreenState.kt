package com.newax.aegis.ui.state

/**
 * Memory screen state — the plain-Kotlin half of the Memory surface (T3.1).
 * The category inventory, counts, display names and the draft-editor parsing
 * used to live inline in the composable; they are here so the decisions are
 * unit-testable and the screen only renders.
 *
 * The holder is stateless: the memory content itself lives in the encrypted
 * store behind `MainViewModel.memory`.
 */
class MemoryScreenState {

    /** The canonical category set rendered by the Memory screen, in display order. */
    val categories = listOf("personal", "business", "education", "relationships", "goals", "pain_points", "rules")

    /** Total facts across all categories — the header card's headline number. */
    fun totalCount(categoryEntries: Map<String, List<String>>): Int =
        categoryEntries.values.sumOf { it.size }

    /** "pain_points" → "Pain points" — the row label for a category key. */
    fun displayName(category: String): String =
        category.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /**
     * The draft editor saves "one fact per line": blank lines are dropped so a
     * trailing newline never persists an empty entry.
     */
    fun parseFacts(draft: String): List<String> = draft.lines().filter { it.isNotBlank() }
}
