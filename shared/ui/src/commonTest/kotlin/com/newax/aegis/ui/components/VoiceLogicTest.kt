package com.newax.aegis.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure logic behind the T3.4c voice components, tested without Compose:
 * [clampAmplitude] keeps the listening bars inside [0, 1] no matter what the
 * recognizer emits.
 */
class VoiceLogicTest {

    // ── clampAmplitude ─────────────────────────────────────────────────────

    @Test
    fun keepsInRangeValues() {
        assertEquals(0f, clampAmplitude(0f))
        assertEquals(0.5f, clampAmplitude(0.5f))
        assertEquals(1f, clampAmplitude(1f))
    }

    @Test
    fun clampsOutOfRangeValues() {
        assertEquals(0f, clampAmplitude(-0.1f), "a negative amplitude must not leave the bars negative")
        assertEquals(1f, clampAmplitude(1.4f), "an over-1.0 amplitude must not stretch the bars")
        assertEquals(0f, clampAmplitude(Float.NEGATIVE_INFINITY))
        assertEquals(1f, clampAmplitude(Float.POSITIVE_INFINITY))
    }
}
