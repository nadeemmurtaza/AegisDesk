package com.newax.aegis.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure decision logic behind the T3.4c settings components, tested
 * without Compose: [tagsAfterAdd] is the "what gets added" rule for
 * [TagEditor].
 */
class SettingsLogicTest {

    // ── tagsAfterAdd ───────────────────────────────────────────────────────

    @Test
    fun trimsTheInput() {
        assertEquals(
            listOf("personal"),
            tagsAfterAdd("  personal  ", emptyList(), 10),
            "leading/trailing whitespace must not become part of the tag",
        )
    }

    @Test
    fun blankInputChangesNothing() {
        val tags = listOf("work")
        assertEquals(tags, tagsAfterAdd("   ", tags, 10))
        assertEquals(tags, tagsAfterAdd("", tags, 10))
    }

    @Test
    fun dedupesCaseInsensitivelyKeepingFirstSpelling() {
        val tags = listOf("Work")
        assertEquals(
            listOf("Work"),
            tagsAfterAdd("work", tags, 10),
            "'work' already exists as 'Work' — nothing is added",
        )
        assertEquals(
            listOf("Work", "Family"),
            tagsAfterAdd("family", tags, 10),
        )
    }

    @Test
    fun capsAtMaxTags() {
        val tags = listOf("a", "b", "c")
        assertEquals(listOf("a", "b"), tagsAfterAdd("x", tags, 2))
        assertEquals(emptyList(), tagsAfterAdd("x", tags, 0))
        assertEquals(listOf("a", "b", "c", "x"), tagsAfterAdd("x", tags, 10))
    }
}
