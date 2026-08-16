package com.newax.aegis.ui.state

import com.newax.aegis.R
import com.newax.aegis.assistant.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3.1 — the chat screen's plain-Kotlin decisions. The holder is stateless and
 * pure, so every branch is verified on the JVM without Compose or Android.
 */
class ChatScreenStateTest {

    private val state = ChatScreenState()

    @Test
    fun `four suggestion chips in the spec order`() {
        assertEquals(
            listOf(
                R.string.chat_suggestion_screen,
                R.string.chat_suggestion_open_app,
                R.string.chat_suggestion_draft_reply,
                R.string.chat_suggestion_what_remember,
            ),
            state.suggestionChips
        )
    }

    @Test
    fun `thread counts as empty only at the boot greeting and when idle`() {
        assertTrue(state.shouldShowEmptyState(0, modelBusy = false))
        assertTrue(state.shouldShowEmptyState(1, modelBusy = false))
        assertFalse(state.shouldShowEmptyState(2, modelBusy = false))
        // A busy model shows the typing indicator, never the empty state.
        assertFalse(state.shouldShowEmptyState(0, modelBusy = true))
        assertFalse(state.shouldShowEmptyState(1, modelBusy = true))
    }

    @Test
    fun `scroll target is the last message, one past it while streaming`() {
        assertEquals(-1, state.scrollTarget(0, streamingActive = false))
        assertEquals(0, state.scrollTarget(1, streamingActive = false))
        assertEquals(4, state.scrollTarget(5, streamingActive = false))
        // The streaming bubble sits after the last message.
        assertEquals(5, state.scrollTarget(5, streamingActive = true))
    }

    @Test
    fun `blank input submits nothing, otherwise the trimmed turn`() {
        assertNull(state.submitText(""))
        assertNull(state.submitText("   "))
        assertNull(state.submitText("\n\t"))
        assertEquals("open spotify", state.submitText("  open spotify  "))
    }

    @Test
    fun `empty transcript keeps the live list untouched`() {
        val live = listOf(ChatMessage("Newax is ready.", false, id = "boot"))
        assertEquals(live, state.mergeTranscript(emptyList(), live, "boot"))
    }

    @Test
    fun `transcript merges with live messages, deduped by id and time-ordered`() {
        val persisted = listOf(
            ChatMessage("older persisted", true, timestamp = 100, id = "p1"),
            ChatMessage("newer persisted", false, timestamp = 300, id = "p2"),
        )
        val live = listOf(
            ChatMessage("same id as persisted", true, timestamp = 250, id = "p1"), // live wins
            ChatMessage("sent while loading", true, timestamp = 200, id = "live"),
        )

        val merged = state.mergeTranscript(persisted, live, "boot")

        // Time order: live(200) < same-id-as-persisted(250, the live copy) < newer persisted(300).
        assertEquals(listOf("live", "same id as persisted", "newer persisted"), merged.map { it.id })
    }

    @Test
    fun `boot greeting is dropped once real history exists`() {
        val persisted = listOf(ChatMessage("hello", true, timestamp = 100, id = "p1"))
        val live = listOf(ChatMessage("Newax is ready.", false, timestamp = 0, id = "boot"))

        val merged = state.mergeTranscript(persisted, live, "boot")

        assertEquals(listOf("p1"), merged.map { it.id })
    }
}
