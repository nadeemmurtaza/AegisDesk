package com.newax.aegis.ui.state

import com.newax.aegis.ui.state.AppsIndexState.AppRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T3.5e — the apps-index filter (route 4.3). The enumeration is a
 * PackageManager query; the filter over name + package is pure and verified
 * here without Android.
 */
class AppsIndexStateTest {

    private val state = AppsIndexState()

    private val apps = listOf(
        AppRow("Messages", "com.google.android.apps.messaging"),
        AppRow("WhatsApp", "com.whatsapp"),
        AppRow("Spotify", "com.spotify.music"),
        AppRow("Gmail", "com.google.android.gm")
    )

    @Test
    fun `blank query returns every app`() {
        assertEquals(apps, state.filter("", apps))
        assertEquals(apps, state.filter("   ", apps))
    }

    @Test
    fun `matches app names case-insensitively`() {
        assertEquals(listOf(apps[1]), state.filter("whats", apps))
        assertEquals(listOf(apps[3]), state.filter("GMAIL", apps))
    }

    @Test
    fun `matches package names too`() {
        assertEquals(listOf(apps[1]), state.filter("com.whatsapp", apps))
        assertEquals(listOf(apps[0]), state.filter("messaging", apps))
    }

    @Test
    fun `multiple words must all match across name and package`() {
        // "mes whats" — "whats" matches Messages' package.
        assertEquals(listOf(apps[0]), state.filter("mes whats", apps))
        // "spot music" — "spot" matches the name, "music" the package.
        assertEquals(listOf(apps[2]), state.filter("spot music", apps))
    }

    @Test
    fun `no match yields an empty list`() {
        assertEquals(emptyList(), state.filter("zzzznope", apps))
    }
}
