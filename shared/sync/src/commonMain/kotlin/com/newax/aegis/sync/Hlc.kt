package com.newax.aegis.sync

/**
 * Hybrid logical clock (docs/SYNC_DESIGN.md §4.1): a (wall, counter) pair that
 * stays monotone even when device wall clocks drift, so merge order never
 * depends on trusting wall time.
 *
 * - [tick]: local event — wall = max(prev.wall, physical now); counter resets
 *   only when the wall advanced, otherwise increments.
 * - [receive]: event caused by observing another device's entry — wall =
 *   max(prev.wall, received.wall, physical now), counter = max of the
 *   colliding counters + 1.
 *
 * Two independent devices can legitimately produce the same (wall, counter);
 * LWW tie-breaks by deviceId at the entry level (see JournalMerge).
 */
data class Hlc(val wall: Long, val counter: Long) : Comparable<Hlc> {

    override fun compareTo(other: Hlc): Int {
        val w = wall.compareTo(other.wall)
        return if (w != 0) w else counter.compareTo(other.counter)
    }

    companion object {
        val ZERO = Hlc(0, 0)

        /** Local event: advance past the physical clock. */
        fun tick(prev: Hlc, physicalNow: Long): Hlc {
            val wall = maxOf(prev.wall, physicalNow)
            return Hlc(wall, if (wall == prev.wall) prev.counter + 1 else 0)
        }

        /** Event caused by observing [received] from another device. */
        fun receive(prev: Hlc, received: Hlc, physicalNow: Long): Hlc {
            val wall = maxOf(prev.wall, received.wall, physicalNow)
            val counter = when {
                wall == prev.wall && wall == received.wall -> maxOf(prev.counter, received.counter) + 1
                wall == prev.wall -> prev.counter + 1
                wall == received.wall -> received.counter + 1
                else -> 0
            }
            return Hlc(wall, counter)
        }
    }
}
