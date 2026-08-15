package com.newax.aegis.sync

/**
 * Proximity discovery for the encrypted Quick Share system (docs/SYNC_DESIGN.md
 * §10.1) — the "find nearby devices" half. Adapters per platform:
 * - jvmAndroidMain: [LanProximityDiscovery] — mDNS `_aegis-proximity._tcp.local.`
 *   (works on the JVM desktops; the Android fallback path).
 * - Android BLE advertise/scan + WiFi-P2P handoff: named next slice (P2) — the
 *   bulk transfer then runs [ProximityTransfer] over the WiFi-Direct socket.
 * - iOS Multipeer Connectivity: Track I.
 */
data class ProximityProfile(
    val deviceId: String,
    val displayName: String,
    /**
     * The TCP port this device accepts proximity transfers on (0 = none).
     * Carried by mDNS TXT on the desktops so the peer can connect directly;
     * BLE advertisements are too small to carry it — the Android path uses
     * the fixed WiFi-Direct port instead.
     */
    val port: Int = 0
)

/**
 * A nearby device. [address]/[port] are the direct transfer hint where the
 * platform provides one (mDNS); BLE-discovered devices fill them in after the
 * WiFi-P2P group forms (P2).
 */
data class ProximityEndpoint(
    val deviceId: String,
    val displayName: String,
    val address: String?,
    val port: Int?
)

interface ProximityListener {
    fun onPeerFound(endpoint: ProximityEndpoint)
}

interface ProximityDiscovery {

    /** Announce this device so nearby devices can find it. */
    fun startAdvertising(profile: ProximityProfile)

    /** Begin scanning; [listener] receives each newly-found peer. */
    fun startScanning(listener: ProximityListener)

    /** Stop advertising and scanning. */
    fun stop()

    /** Peers currently visible to discovery, newest last. */
    fun nearby(): List<ProximityEndpoint>

    /**
     * Why discovery is degraded, or null when it is healthy.
     *
     * Defaults to null so implementations without a diagnostic channel need no
     * change. [LanProximityDiscovery] sets it on advertise failure, scan
     * failure, and mDNS being unavailable — cases where discovery keeps running
     * but finds nothing, which is otherwise indistinguishable from "no peers
     * nearby". The desktop CLI already tried to read this and did not compile.
     */
    val error: String? get() = null
}

/** Platform seam — the actuals are per-target (jvmAndroidMain: mDNS; P2: BLE). */
expect fun proximityDiscovery(): ProximityDiscovery
