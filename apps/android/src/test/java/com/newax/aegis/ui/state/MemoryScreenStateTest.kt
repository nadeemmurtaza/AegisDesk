package com.newax.aegis.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

/** T3.1 — the memory screen's plain-Kotlin decisions (categories, counts, parsing). */
class MemoryScreenStateTest {

    private val state = MemoryScreenState()

    @Test
    fun `canonical category set is stable and in display order`() {
        assertEquals(
            listOf("personal", "business", "education", "relationships", "goals", "pain_points", "rules"),
            state.categories
        )
    }

    @Test
    fun `total count sums every category and is zero for empty memory`() {
        assertEquals(0, state.totalCount(emptyMap()))
        assertEquals(0, state.totalCount(mapOf("personal" to emptyList(), "goals" to emptyList())))
        assertEquals(
            5,
            state.totalCount(
                mapOf("personal" to listOf("a", "b"), "goals" to listOf("c", "d", "e"))
            )
        )
    }

    @Test
    fun `display name turns underscore keys into title-case labels`() {
        assertEquals("Personal", state.displayName("personal"))
        assertEquals("Pain points", state.displayName("pain_points"))
        assertEquals("Rules", state.displayName("rules"))
    }

    @Test
    fun `parse facts drops blank lines so trailing newlines store nothing`() {
        assertEquals(listOf("buy milk", "call mom"), state.parseFacts("buy milk\n\ncall mom\n"))
        assertEquals(emptyList(), state.parseFacts(""))
        assertEquals(emptyList(), state.parseFacts("   \n\n"))
    }
}
