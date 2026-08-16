package com.newax.aegis.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure SAS helpers behind the pairing cards, tested without Compose
 * (docs/SYNC_DESIGN.md §pairing — human verification must never depend on a
 * display quirk: both devices derive the same grouped code from the same raw
 * code, and a mismatch is a mismatch regardless of case or separators).
 */
class PairingLogicTest {

    // ── sasGrouped ─────────────────────────────────────────────────────────

    @Test
    fun groupsAndUppercases() {
        assertEquals("2C4-K7Q", sasGrouped("2c4k7q"))
    }

    @Test
    fun dropsNonAlphanumerics() {
        assertEquals("2C4-K7Q", sasGrouped("2c-4k_7q"))
    }

    @Test
    fun shortCodeIsShownAsIs() {
        assertEquals("AB", sasGrouped("ab"))
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", sasGrouped(""))
        assertEquals("", sasGrouped(" -_ "))
    }

    // ── sasCodesMatch ──────────────────────────────────────────────────────

    @Test
    fun equalCodesMatchIgnoringCaseAndFormatting() {
        assertTrue(sasCodesMatch("2c4k7q", "2C4-K7Q"))
        assertTrue(sasCodesMatch("2C4-K7Q", "2c4k7q"))
    }

    @Test
    fun differentCodesDoNotMatch() {
        assertFalse(sasCodesMatch("2c4k7q", "2c4k7r"))
        assertFalse(sasCodesMatch("abc", "abd"))
    }

    @Test
    fun blankNeverMatches() {
        assertFalse(sasCodesMatch("", "2C4-K7Q"))
        assertFalse(sasCodesMatch("2C4-K7Q", ""))
        assertFalse(sasCodesMatch("", ""))
    }
}
