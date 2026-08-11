package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlcTest {

    @Test
    fun tickAdvancesWallWhenPhysicalTimeMoves() {
        val t1 = Hlc.tick(Hlc.ZERO, physicalNow = 100)
        assertEquals(Hlc(100, 0), t1)
        val t2 = Hlc.tick(t1, physicalNow = 200)
        assertEquals(Hlc(200, 0), t2)
    }

    @Test
    fun tickIncrementsCounterWhenWallIsStuck() {
        val t1 = Hlc.tick(Hlc.ZERO, physicalNow = 100)
        val t2 = Hlc.tick(t1, physicalNow = 100) // clock did not move
        assertEquals(Hlc(100, 1), t2)
        val t3 = Hlc.tick(t2, physicalNow = 100)
        assertEquals(Hlc(100, 2), t3)
    }

    @Test
    fun tickNeverGoesBackwardsWhenClockDrifts() {
        val t1 = Hlc.tick(Hlc.ZERO, physicalNow = 500)
        val t2 = Hlc.tick(t1, physicalNow = 300) // physical clock jumped back
        assertEquals(Hlc(500, 1), t2)
    }

    @Test
    fun receiveCollidesCounters() {
        // Two devices independently produce (100, 0); observing each other must
        // yield a strictly greater hlc than both.
        val mine = Hlc(100, 0)
        val theirs = Hlc(100, 0)
        val merged = Hlc.receive(mine, theirs, physicalNow = 90)
        assertTrue(merged > mine)
        assertTrue(merged > theirs)
        assertEquals(Hlc(100, 1), merged)
    }

    @Test
    fun receiveTakesMaxCounterOnCollision() {
        val mine = Hlc(100, 3)
        val theirs = Hlc(100, 7)
        assertEquals(Hlc(100, 8), Hlc.receive(mine, theirs, physicalNow = 90))
    }

    @Test
    fun receiveTakesLaterWallWhenReceivedIsAhead() {
        val mine = Hlc(100, 5)
        val theirs = Hlc(120, 0)
        assertEquals(Hlc(120, 1), Hlc.receive(mine, theirs, physicalNow = 90))
    }

    @Test
    fun comparisonIsWallThenCounter() {
        assertTrue(Hlc(1, 0) < Hlc(2, 0))
        assertTrue(Hlc(2, 1) < Hlc(2, 2))
        assertTrue(Hlc(2, 2) == Hlc(2, 2))
        assertTrue(Hlc(0, 0) < Hlc(1, 0))
    }
}
