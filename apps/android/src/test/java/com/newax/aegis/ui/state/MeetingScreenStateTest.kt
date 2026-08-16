package com.newax.aegis.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3.1 — the meeting screen's plain-Kotlin decisions (wire format, parse, prepend). */
class MeetingScreenStateTest {

    private val state = MeetingScreenState()

    @Test
    fun `new entry stores the trimmed title with the epoch timestamp`() {
        assertEquals("Sprint Review :: 1700000000000", state.newEntry("Sprint Review", 1700000000000))
        assertEquals("Sprint Review :: 1700000000000", state.newEntry("  Sprint Review  ", 1700000000000))
    }

    @Test
    fun `parse splits title and timestamp`() {
        val parsed = state.parseEntry("Sprint Review :: 1700000000000")
        assertEquals("Sprint Review", parsed.title)
        assertEquals(1700000000000L, parsed.timestampMillis)
    }

    @Test
    fun `malformed rows keep the whole entry as the title and no timestamp`() {
        val parsed = state.parseEntry("no separator here")
        assertEquals("no separator here", parsed.title)
        assertNull(parsed.timestampMillis)

        val junk = state.parseEntry("title :: not-a-number")
        assertEquals("title", junk.title)
        assertNull(junk.timestampMillis)
    }

    @Test
    fun `start requires a non-blank title`() {
        assertTrue(state.canStart("Sprint Review"))
        assertTrue(state.canStart(" 1:1 "))
        assertFalse(state.canStart(""))
        assertFalse(state.canStart("   "))
    }

    @Test
    fun `add prepends so the newest meeting is first`() {
        val updated = state.addMeeting(listOf("older", "oldest"), "newest")
        assertEquals(listOf("newest", "older", "oldest"), updated)
        // The original list is not mutated.
        assertEquals(2, state.addMeeting(emptyList(), "only").size)
    }
}
