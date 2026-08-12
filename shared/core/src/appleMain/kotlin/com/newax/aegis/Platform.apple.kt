package com.newax.aegis

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.timeIntervalSince1970

@OptIn(ExperimentalForeignApi::class)
actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

private val platformLock = NSLock()

actual fun <T> withLock(lock: Any, block: () -> T): T {
    platformLock.lock()
    try {
        return block()
    } finally {
        platformLock.unlock()
    }
}
