package com.newax.aegis.sync

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Android actual for [acquireMulticastLock]: mDNS (JmDNS) on Android only
 * receives multicast announcements while a `WifiManager.MulticastLock` is
 * held. Non-fatal by design — if the lock cannot be acquired the transport
 * degrades to direct connections exactly as when mDNS is otherwise blocked.
 */
actual fun acquireMulticastLock(): MulticastLockHandle? {
    val context = AndroidSyncContext.requireContext()
    val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    return try {
        val lock = wifi.createMulticastLock("aegis-sync")
        lock.setReferenceCounted(false)
        lock.acquire()
        object : MulticastLockHandle {
            override fun release() {
                try {
                    lock.release()
                } catch (_: Exception) {
                    // Already released / WiFi torn down — nothing to do.
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}
