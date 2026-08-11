package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionVectorTest {

    @Test
    fun watermarkDefaultsToZero() {
        assertEquals(Hlc.ZERO, VersionVector.EMPTY.watermarkFor("dev-w"))
    }

    @Test
    fun advanceSetsAndGrowsMonotonically() {
        val v = VersionVector.EMPTY
            .advance("dev-w", Hlc(3, 1))
            .advance("dev-w", Hlc(2, 9)) // lower wall — must not regress
        assertEquals(Hlc(3, 1), v.watermarkFor("dev-w"))
    }

    @Test
    fun mergeTakesPerPeerMax() {
        val a = VersionVector(mapOf("w" to Hlc(5, 0), "m" to Hlc(1, 0)))
        val b = VersionVector(mapOf("w" to Hlc(3, 0), "m" to Hlc(7, 0), "i" to Hlc(2, 0)))
        val merged = a.merge(b)
        assertEquals(Hlc(5, 0), merged.watermarkFor("w"))
        assertEquals(Hlc(7, 0), merged.watermarkFor("m"))
        assertEquals(Hlc(2, 0), merged.watermarkFor("i"))
    }

    @Test
    fun dominance() {
        val ahead = VersionVector(mapOf("w" to Hlc(5, 0), "m" to Hlc(4, 0)))
        val behind = VersionVector(mapOf("w" to Hlc(3, 0), "m" to Hlc(4, 0)))
        assertTrue(ahead.dominates(behind))
        assertFalse(behind.dominates(ahead))
    }
}
