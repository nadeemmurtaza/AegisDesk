package com.newax.aegis.desktop.ui.state

import com.newax.aegis.platform.windows.AppIndexBridge
import com.newax.aegis.platform.windows.AppIndexEntry
import com.newax.aegis.platform.windows.WindowsAppIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase B1 — the Apps screen state holder: query state plus the index match
 * set, tested against a fake Start Menu bridge (the index itself is verified
 * by the platform module's WindowsAppIndexTest).
 */
class AppsScreenStateTest {

    private val entries = listOf(
        AppIndexEntry("Spotify", "Music", "C:\\Programs\\Music\\Spotify.lnk"),
        AppIndexEntry("Notepad", "Accessories", "C:\\Programs\\Accessories\\Notepad.lnk"),
        AppIndexEntry("Steam", "Games", "C:\\Programs\\Games\\Steam.lnk"),
    )

    @Test
    fun `matches are empty when the index is unavailable`() {
        val state = AppsScreenState(indexProvider = { null })

        assertNull(state.index())
        assertTrue(state.matches().isEmpty())
    }

    @Test
    fun `blank query lists every indexed app`() {
        val state = AppsScreenState(indexProvider = { indexOf(entries) })

        assertEquals(3, state.matches().size)
    }

    @Test
    fun `query filters through the index search`() {
        val state = AppsScreenState(indexProvider = { indexOf(entries) })

        state.setQuery("spot")
        assertEquals(listOf("Spotify"), state.matches().map { it.name })
    }

    @Test
    fun `no match yields an empty result`() {
        val state = AppsScreenState(indexProvider = { indexOf(entries) })

        state.setQuery("zzz")
        assertTrue(state.matches().isEmpty())
    }

    @Test
    fun `clearing the query restores the full list`() {
        val state = AppsScreenState(indexProvider = { indexOf(entries) })

        state.setQuery("spot")
        state.setQuery("")
        assertEquals(3, state.matches().size)
    }

    private fun indexOf(entries: List<AppIndexEntry>): WindowsAppIndex =
        WindowsAppIndex(FakeAppIndexBridge(entries))

    private class FakeAppIndexBridge(private val entries: List<AppIndexEntry>) : AppIndexBridge {
        override fun enumerate(): List<AppIndexEntry> = entries
    }
}
