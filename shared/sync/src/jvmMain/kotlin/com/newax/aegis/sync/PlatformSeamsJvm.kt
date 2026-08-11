package com.newax.aegis.sync

import java.io.File

/**
 * The JVM desktop platform seams (docs/SYNC_DESIGN.md §3, §10.1): mDNS
 * proximity discovery ([LanProximityDiscovery]) and the dev [FileKeyStore].
 * Both actuals live in jvmMain (JVM target only) so androidMain can own the
 * Android production actuals (BLE/WiFi-Direct discovery, TEE-wrapped
 * [AndroidSyncKeyStore]) — each compiled target sees exactly one
 * implementation of each commonMain expect.
 */
actual fun proximityDiscovery(): ProximityDiscovery = LanProximityDiscovery()

actual fun platformWsClient(): WsClient = JvmWsClient()

actual fun platformKeyStore(): KeyStore {
    val home = System.getProperty("user.home") ?: "."
    return FileKeyStore(File(home, ".aegis/keys"))
}

/** Desktop mDNS needs no multicast lock — plain sockets work out of the box. */
actual fun acquireMulticastLock(): MulticastLockHandle? = null
