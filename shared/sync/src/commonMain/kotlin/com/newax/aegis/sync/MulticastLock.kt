package com.newax.aegis.sync

/**
 * Optional platform hook for mDNS discovery. Android's multicast sockets need
 * a `WifiManager.MulticastLock` held for the lifetime of the JmDNS instance,
 * or discovery silently finds nothing; the JVM desktop actual is a no-op.
 * The transport acquires the handle when it starts mDNS and releases it on
 * [SyncTransport.stop] — never per-scan (the lock must span the whole
 * discovery lifetime).
 */
interface MulticastLockHandle {
    fun release()
}

/** Platform seam: acquire the multicast lock, or null where not supported. */
expect fun acquireMulticastLock(): MulticastLockHandle?
