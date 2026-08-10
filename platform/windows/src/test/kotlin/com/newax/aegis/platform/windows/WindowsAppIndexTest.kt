package com.newax.aegis.platform.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5i — WindowsAppIndex (Start Menu enumeration + fuzzy search) is tested
 * against a fake [AppIndexBridge], so the search semantics and the honest
 * non-Windows empty index are verified without a Windows OS. The real
 * [Win32AppIndexBridge] filesystem walk is a Windows-machine concern (its
 * behavior here on Linux — empty roots, empty index — is itself the honest
 * fallback the facade reports).
 */
class WindowsAppIndexTest {

    private class FakeBridge(var entries: List<AppIndexEntry> = emptyList()) : AppIndexBridge {
        override fun enumerate(): List<AppIndexEntry> = entries
    }

    @Test
    fun `null bridge yields an honest empty index`() {
        // The production constructor passes null on non-Windows — the index must
        // report empty, never invent apps.
        val index = WindowsAppIndex(bridge = null)
        assertTrue(index.all().isEmpty())
        assertTrue(index.search("spotify").isEmpty())
    }

    @Test
    fun `search matches names case-insensitively and across tokens`() {
        val index = WindowsAppIndex(
            FakeBridge(
                listOf(
                    AppIndexEntry("Spotify", "Music", "C:\\start\\Spotify.lnk"),
                    AppIndexEntry("Visual Studio Code", "Development", "C:\\start\\Code.lnk"),
                )
            )
        )
        assertEquals(listOf("Spotify"), index.search("spotify").map { it.name })
        assertEquals(listOf("Spotify"), index.search("SPOTIFY").map { it.name })
        assertEquals(listOf("Visual Studio Code"), index.search("studio code").map { it.name })
        assertEquals(listOf("Visual Studio Code"), index.search("VISUAL").map { it.name })
    }

    @Test
    fun `search ranks exact name above prefix above contains`() {
        val index = WindowsAppIndex(
            FakeBridge(
                listOf(
                    AppIndexEntry("Spotify Music Player", "Music", "C:\\a\\SMP.lnk"),
                    AppIndexEntry("Spotify", "Music", "C:\\a\\Spotify.lnk"),
                    AppIndexEntry("Spoti", "Other", "C:\\a\\Spoti.lnk"),
                )
            )
        )
        assertEquals(listOf("Spotify", "Spotify Music Player"), index.search("spotify").map { it.name })
    }

    @Test
    fun `blank query returns nothing`() {
        val index = WindowsAppIndex(
            FakeBridge(listOf(AppIndexEntry("Spotify", "Music", "C:\\a\\Spotify.lnk")))
        )
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun `all returns the enumerated entries in name order`() {
        val index = WindowsAppIndex(
            FakeBridge(
                listOf(
                    AppIndexEntry("Zoom", "Communications", "C:\\a\\Zoom.lnk"),
                    AppIndexEntry("Aegis", "Installed", "C:\\a\\Aegis.lnk"),
                )
            )
        )
        assertEquals(listOf("Aegis", "Zoom"), index.all().map { it.name })
    }
}
