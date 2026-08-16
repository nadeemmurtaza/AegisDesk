package com.newax.aegis.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure logic behind the shared components, tested without Compose.
 *
 * The components themselves need a UI harness for their semantics/screenshot
 * gate (docs/UI_DESIGN.md §3.6) — that harness is a Track 1 dependency ask.
 * What is testable here is the decision logic, extracted into plain functions
 * so the gate can never silently weaken:
 */
class ComponentLogicTest {

    @Test
    fun typeToConfirmGateRequiresExactMatch() {
        assertTrue(confirmPhraseMatches("ERASE", "ERASE"))
        assertTrue(confirmPhraseMatches("  ERASE  ", "ERASE"), "surrounding whitespace is user forgiveness, not a second phrase")
        assertTrue(confirmPhraseMatches("erase now", "erase now"))
    }

    @Test
    fun typeToConfirmGateRejectsMismatch() {
        assertFalse(confirmPhraseMatches("erase", "ERASE"), "case matters — a wrong-case match is still a wrong match")
        assertFalse(confirmPhraseMatches("ERASE ", "ERASE NOW"))
        assertFalse(confirmPhraseMatches("ERAS", "ERASE"))
        assertFalse(confirmPhraseMatches("", "ERASE"), "empty input must never confirm")
    }

    @Test
    fun typeToConfirmGateIsClosedWhenPhraseIsBlank() {
        // A caller that forgets the phrase gets a permanently disabled confirm
        // — never a trivially confirmable dialog.
        assertFalse(confirmPhraseMatches("anything", ""))
        assertFalse(confirmPhraseMatches("anything", "   "))
    }
}
