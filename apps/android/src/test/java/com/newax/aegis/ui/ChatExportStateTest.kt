package com.newax.aegis.ui

import com.newax.aegis.assistant.ChatMessage
import com.newax.aegis.ui.state.ChatExportFormat
import com.newax.aegis.ui.state.ChatExportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route 1.12 — the conversation export. The renderers are pure, so the exact
 * bytes are pinned here: Markdown keeps its role headings and paragraph
 * breaks, plain text is one line per message, and JSON escapes every piece of
 * message text as data (R12 — a quote or newline inside a message must never
 * break out of the string literal).
 */
class ChatExportStateTest {

    private val state = ChatExportState()

    private fun msg(text: String, fromUser: Boolean, id: String = "m-$text") =
        ChatMessage(text = text, fromUser = fromUser, timestamp = 100L, id = id)

    private val transcript = listOf(
        msg("hi", fromUser = true, id = "a"),
        msg("hello there", fromUser = false, id = "b"),
    )

    @Test
    fun `markdown renders role headings and preserves text`() {
        assertEquals(
            "# Travel plan\n\n" +
                "_Exported from Newax Aegis_\n\n" +
                "**You:** hi\n\n" +
                "**Newax Aegis:** hello there",
            state.render(transcript, ChatExportFormat.MARKDOWN, title = "Travel plan"),
        )
    }

    @Test
    fun `markdown preserves blank lines inside a message`() {
        val withBreak = listOf(msg("line one\nline two", fromUser = false))
        val out = state.render(withBreak, ChatExportFormat.MARKDOWN, title = "t")
        assertTrue(out.contains("**Newax Aegis:** line one\n\nline two"))
    }

    @Test
    fun `markdown falls back to the generic heading for a blank title`() {
        assertTrue(state.render(transcript, ChatExportFormat.MARKDOWN, title = "").startsWith("# conversation"))
    }

    @Test
    fun `plain text renders one line per message`() {
        assertEquals(
            "You: hi\nNewax Aegis: hello there",
            state.render(transcript, ChatExportFormat.TEXT),
        )
    }

    @Test
    fun `json renders role text and timestamp`() {
        assertEquals(
            """[{"role":"user","text":"hi","timestamp":100},{"role":"assistant","text":"hello there","timestamp":100}]""",
            state.render(transcript, ChatExportFormat.JSON),
        )
    }

    @Test
    fun `json escapes quotes backslashes and newlines inside message text`() {
        val nasty = msg("said \"hi\" with \\ and\nnewline", fromUser = true)
        val out = state.render(listOf(nasty), ChatExportFormat.JSON)
        assertTrue(out.contains("\"text\":\"said \\\"hi\\\" with \\\\ and\\nnewline\""))
        // The escaped message must never break the array's structure.
        assertEquals(1, out.count { it == '[' })
        assertEquals(1, out.count { it == ']' })
    }

    @Test
    fun `json escapes control characters below space as unicode escapes`() {
        val ctrl = msg("bell\u0007 tab\t", fromUser = false)
        val out = state.render(listOf(ctrl), ChatExportFormat.JSON)
        assertTrue(out.contains("\\u0007"))
        assertTrue(out.contains("\\t"))
    }

    @Test
    fun `empty transcript renders empty for every format`() {
        ChatExportFormat.entries.forEach { format ->
            assertEquals("", state.render(emptyList(), format))
        }
    }

    @Test
    fun `file name sanitizes invalid characters and falls back to conversation`() {
        val now = 1_720_000_000_000L
        assertEquals("Travel plan Q3-${now}.md", state.exportFileName("Travel/plan: Q3?", ChatExportFormat.MARKDOWN, now))
        assertEquals("conversation-${now}.json", state.exportFileName("   ", ChatExportFormat.JSON, now))
        assertEquals("conversation-${now}.txt", state.exportFileName("\u0000\u0001", ChatExportFormat.TEXT, now))
    }

    @Test
    fun `file name is unique per export of the same title`() {
        val a = state.exportFileName("Chat", ChatExportFormat.MARKDOWN, 1L)
        val b = state.exportFileName("Chat", ChatExportFormat.MARKDOWN, 2L)
        assertTrue(a != b)
        assertTrue(a.endsWith(".md") && b.endsWith(".md"))
    }

    @Test
    fun `file name caps an overlong title without dropping the extension`() {
        val name = state.exportFileName("x".repeat(200), ChatExportFormat.TEXT, 1L)
        assertTrue(name.length < 60)
        assertTrue(name.endsWith(".txt"))
    }

    @Test
    fun `custom role labels render through markdown and text`() {
        val labels = "Ich" to "Newax Aegis"
        assertEquals("Ich: hi\nNewax Aegis: hello there", state.render(transcript, ChatExportFormat.TEXT, roleLabels = labels))
        assertTrue(state.render(transcript, ChatExportFormat.MARKDOWN, roleLabels = labels).contains("**Ich:** hi"))
    }
}
