package com.newax.aegis.sync

import java.util.concurrent.CopyOnWriteArrayList
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * The JVM/Android base proximity discovery: mDNS advertisement + scanning of
 * `_aegis-proximity._tcp.local.` services (deviceId/displayName/port in TXT
 * props). The JVM desktop actual is `proximityDiscovery()` in jvmMain; on
 * Android the BLE + WiFi-P2P actual (P2) is the default, and this remains
 * available as the LAN fallback for hosts where mDNS is reachable.
 */
class LanProximityDiscovery : ProximityDiscovery {

    companion object {
        const val SERVICE_TYPE = "_aegis-proximity._tcp.local."
        private const val PROP_DEVICE_ID = "deviceId"
        private const val PROP_DISPLAY_NAME = "displayName"
    }

    @Volatile
    private var mdns: JmDNS? = null
    @Volatile
    private var listener: ProximityListener? = null
    @Volatile
    private var ownDeviceId: String? = null
    private val found = CopyOnWriteArrayList<ProximityEndpoint>()

    /** Non-null when mDNS could not start — discovery is off, not fatal. */
    @Volatile
    var error: String? = null
        private set

    override fun startAdvertising(profile: ProximityProfile) {
        ownDeviceId = profile.deviceId
        val mdns = ensureMdns() ?: return
        try {
            val info = ServiceInfo.create(
                SERVICE_TYPE,
                "aegis-prox-" + profile.deviceId.removePrefix("dev-"),
                profile.port,
                0,
                0,
                mapOf(
                    PROP_DEVICE_ID to profile.deviceId,
                    PROP_DISPLAY_NAME to profile.displayName
                )
            )
            mdns.registerService(info)
        } catch (e: Exception) {
            error = "advertise failed: ${e.message}"
        }
    }

    override fun startScanning(listener: ProximityListener) {
        this.listener = listener
        val mdns = ensureMdns() ?: return
        try {
            mdns.addServiceListener(SERVICE_TYPE, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    mdns.requestServiceInfo(SERVICE_TYPE, event.name, true)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    event.info?.let { info ->
                        deviceIdOf(info)?.let { deviceId ->
                            found.removeAll { it.deviceId == deviceId }
                        }
                    }
                }

                override fun serviceResolved(event: ServiceEvent) {
                    endpointOf(event.info)?.let { endpoint ->
                        if (endpoint.deviceId != ownDeviceId) {
                            found.removeAll { it.deviceId == endpoint.deviceId }
                            found.add(endpoint)
                            listener.onPeerFound(endpoint)
                        }
                    }
                }
            })
            Thread {
                try {
                    for (info in mdns.list(SERVICE_TYPE)) {
                        endpointOf(info)?.let { endpoint ->
                            if (endpoint.deviceId != ownDeviceId) {
                                found.add(endpoint)
                                listener.onPeerFound(endpoint)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // transient browse failure — live listener keeps working
                }
            }.apply {
                isDaemon = true
                name = "aegis-proximity-sweep"
                start()
            }
        } catch (e: Exception) {
            error = "scan failed: ${e.message}"
        }
    }

    override fun stop() {
        try {
            mdns?.close()
        } catch (_: Exception) {
        }
        mdns = null
        found.clear()
    }

    override fun nearby(): List<ProximityEndpoint> = found.toList()

    private fun ensureMdns(): JmDNS? {
        mdns?.let { return it }
        return try {
            val created = JmDNS.create()
            mdns = created
            created
        } catch (e: Exception) {
            error = "mDNS unavailable: ${e.message}"
            null
        }
    }

    private fun deviceIdOf(info: ServiceInfo): String? = info.getPropertyString(PROP_DEVICE_ID)

    private fun endpointOf(info: ServiceInfo): ProximityEndpoint? {
        val deviceId = deviceIdOf(info) ?: return null
        val displayName = info.getPropertyString(PROP_DISPLAY_NAME) ?: deviceId
        return ProximityEndpoint(deviceId, displayName, info.hostAddress, info.port)
    }
}
