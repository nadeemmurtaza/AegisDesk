package com.newax.aegis

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun <T> withLock(lock: Any, block: () -> T): T = synchronized(lock, block)
