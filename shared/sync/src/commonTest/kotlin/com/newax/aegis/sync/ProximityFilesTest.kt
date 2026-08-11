package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/** The received-file name sanitizer (R12 — a remote name is data, never a path). */
class ProximityFilesTest {

    @Test
    fun stripsPathSeparators() {
        assertEquals("a_b.txt", ProximityFiles.safeName("a/b.txt"))
        assertEquals("a_b.txt", ProximityFiles.safeName("a\\b.txt"))
        assertEquals("_secret", ProximityFiles.safeName("../secret"))
    }

    @Test
    fun stripsNulAndLeadingDots() {
        assertEquals("secret", ProximityFiles.safeName(".secret"))
        assertEquals("passwd", ProximityFiles.safeName("..passwd"))
        assertEquals("a_b", ProximityFiles.safeName("a\u0000b"))
    }

    @Test
    fun blankFallsBack() {
        assertEquals("unnamed", ProximityFiles.safeName(""))
        assertEquals("unnamed", ProximityFiles.safeName("   "))
        assertEquals("unnamed", ProximityFiles.safeName("..."))
    }

    @Test
    fun capsLength() {
        assertEquals(120, ProximityFiles.safeName("x".repeat(500)).length)
    }
}
