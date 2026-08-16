package com.newax.aegis.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3.1 — the settings screen's plain-Kotlin decisions (model readiness, ambient toggle). */
class SettingsScreenStateTest {

    private val state = SettingsScreenState()

    @Test
    fun `model is ready when the status contains the word, any case`() {
        assertTrue(state.isModelReady("Offline AI ready • model.bin"))
        assertTrue(state.isModelReady("READY"))
        assertFalse(state.isModelReady("No model installed"))
        assertFalse(state.isModelReady("Importing and verifying model…"))
        assertFalse(state.isModelReady("Model unavailable: load failed"))
        assertFalse(state.isModelReady(""))
    }

    @Test
    fun `ambient modes are the two supported chips`() {
        assertEquals(listOf("Meeting", "Lecture"), state.ambientModes)
    }

    @Test
    fun `selecting the active mode ends ambient mode`() {
        assertNull(state.ambientToggle("Meeting", "Meeting"))
        assertNull(state.ambientToggle("Lecture", "Lecture"))
    }

    @Test
    fun `selecting another mode switches to it`() {
        assertEquals("Lecture", state.ambientToggle("Meeting", "Lecture"))
        assertEquals("Meeting", state.ambientToggle("Lecture", "Meeting"))
    }

    @Test
    fun `no active mode means any selection starts ambient mode`() {
        assertEquals("Meeting", state.ambientToggle(null, "Meeting"))
    }
}
