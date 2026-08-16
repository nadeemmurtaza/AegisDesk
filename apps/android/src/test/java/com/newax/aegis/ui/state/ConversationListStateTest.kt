package com.newax.aegis.ui.state

import com.newax.aegis.chat.MAX_CONVERSATION_TITLE_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3.5a — the conversation-list decisions, tested without Compose: the relative
 * time labels, the rename validation (the store cap is the single source), and
 * the search-active predicate. `now` is passed in explicitly so every case is
 * deterministic.
 */
class ConversationListStateTest {

    private val state = ConversationListState()
    private val now = 1_000_000_000_000L // fixed "now" for every label test

    @Test
    fun `time labels - under a minute says just now`() {
        assertEquals("just now", state.relativeTimeLabel(now - 30_000L, now))
        assertEquals("just now", state.relativeTimeLabel(now - 59_000L, now))
    }

    @Test
    fun `time labels - minutes hours days weeks months years`() {
        assertEquals("5m ago", state.relativeTimeLabel(now - 5 * 60_000L, now))
        assertEquals("59m ago", state.relativeTimeLabel(now - 59 * 60_000L, now))
        assertEquals("1h ago", state.relativeTimeLabel(now - 60 * 60_000L, now))
        assertEquals("23h ago", state.relativeTimeLabel(now - 23 * 3_600_000L, now))
        assertEquals("1d ago", state.relativeTimeLabel(now - 24 * 3_600_000L, now))
        assertEquals("6d ago", state.relativeTimeLabel(now - 6 * 24 * 3_600_000L, now))
        assertEquals("1w ago", state.relativeTimeLabel(now - 7 * 24 * 3_600_000L, now))
        assertEquals("3w ago", state.relativeTimeLabel(now - 21 * 24 * 3_600_000L, now))
        assertEquals("1mo ago", state.relativeTimeLabel(now - 30 * 24 * 3_600_000L, now))
        assertEquals("11mo ago", state.relativeTimeLabel(now - 330 * 24 * 3_600_000L, now))
        assertEquals("1y ago", state.relativeTimeLabel(now - 365 * 24 * 3_600_000L, now))
    }

    @Test
    fun `time label - a clock that moved backwards is clamped to just now`() {
        assertEquals("just now", state.relativeTimeLabel(now + 10_000L, now))
    }

    @Test
    fun `rename - blank input is rejected, whitespace trimmed`() {
        assertNull(state.renameTitle(""))
        assertNull(state.renameTitle("   "))
        assertNull(state.renameTitle("\t\n"))
    }

    @Test
    fun `rename - trims and preserves inner text`() {
        assertEquals("Dinner plans", state.renameTitle("  Dinner plans  "))
    }

    @Test
    fun `rename - caps at the store's single title cap`() {
        val long = "x".repeat(MAX_CONVERSATION_TITLE_CHARS + 50)
        val renamed = state.renameTitle(long)
        assertEquals(MAX_CONVERSATION_TITLE_CHARS, renamed!!.length)
    }

    @Test
    fun `search - only a non-blank query activates the search surface`() {
        assertFalse(state.isSearchActive(""))
        assertFalse(state.isSearchActive("   "))
        assertTrue(state.isSearchActive("dinner"))
    }
}
