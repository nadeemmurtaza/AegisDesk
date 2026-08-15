package com.newax.aegis.db

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * Apple actual for the module's time seam.
 *
 * Uses Foundation rather than a datetime library: `kotlinx.datetime.Clock`
 * moved to `kotlin.time` in kotlinx-datetime 0.8.0, so `Clock.System` no longer
 * resolves here. `NSDate` is always present on Apple targets and needs no
 * dependency at all, which is the smaller surface for something this trivial.
 */
actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
