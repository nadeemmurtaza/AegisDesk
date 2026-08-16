package com.newax.aegis.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3.5e — the memory-search display half (routes 2.1/2.2). The ranking is the
 * encrypted store's TF-IDF; what is verified here is the pure UI logic: when a
 * query is worth searching, mapping a ranked fact back to its owning category
 * (so the hit opens route 2.3's editor), and the highlight range for the
 * matched term.
 */
class MemorySearchStateTest {

    private val state = MemorySearchState()

    @Test
    fun `blank and one-char queries are inactive`() {
        assertFalse(state.isActive(""))
        assertFalse(state.isActive("   "))
        assertFalse(state.isActive("a"))
    }

    @Test
    fun `two-char queries are active`() {
        assertTrue(state.isActive("al"))
        assertTrue(state.isActive("  al  "))
    }

    @Test
    fun `categoryOf finds the owning category case-insensitively`() {
        val allCats = mapOf(
            "personal" to listOf("Likes dark roast coffee", "Birthday in March"),
            "business" to listOf("Quarterly review on Friday")
        )
        assertEquals("business", state.categoryOf("quarterly review on friday", allCats))
        assertEquals("personal", state.categoryOf("Likes dark roast coffee", allCats))
    }

    @Test
    fun `categoryOf returns null when the fact is not in any category`() {
        val allCats = mapOf("personal" to listOf("One fact"))
        assertNull(state.categoryOf("forgotten fact", allCats))
        assertNull(state.categoryOf("", allCats))
    }

    @Test
    fun `highlightRange marks the longest matching word`() {
        val range = state.highlightRange("meeting with Ali at 5pm", "ali meeting")
        // "meeting" (7 chars) beats "ali" (3) — the range is the meeting match.
        assertEquals(0..6, range)
    }

    @Test
    fun `highlightRange is case-insensitive`() {
        assertEquals(11..13, state.highlightRange("meeting with ali at 5pm", "ALI"))
    }

    @Test
    fun `highlightRange returns null when nothing matches`() {
        assertNull(state.highlightRange("one fact", "zzz"))
        assertNull(state.highlightRange("one fact", ""))
        assertNull(state.highlightRange("one fact", "   "))
    }

    @Test
    fun `highlightRange finds a match in the middle of the fact`() {
        val fact = "Prefers mornings for deep work"
        val range = state.highlightRange(fact, "mornings")
        assertEquals(fact.indexOf("mornings"), range?.first)
    }
}
