package com.newax.aegis

/**
 * Wall-clock epoch milliseconds — the only platform-dependent time primitive
 * commonMain may use (invariant 5: commonMain is platform-free). Actuals live
 * in jvmMain (System.currentTimeMillis), androidMain (same), and appleMain
 * (NSDate).
 */
expect fun currentTimeMillis(): Long

/**
 * Mutual-exclusion lock over [block] — the platform-free twin of JVM's
 * `synchronized`, which does not exist in Kotlin/Native. Actual: JVM/Android
 * builtin `synchronized`; Apple: NSLock. Not inline, so blocks must not use
 * non-local returns.
 */
expect fun <T> withLock(lock: Any, block: () -> T): T
