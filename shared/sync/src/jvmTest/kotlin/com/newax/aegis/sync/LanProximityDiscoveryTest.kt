package com.newax.aegis.sync

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real-JmDNS proximity discovery: two LanProximityDiscovery instances on the
 * same host advertise + scan and must find each other (filtering themselves
 * out). Skipped on multicast-less hosts, like the LAN discovery test.
 */
class LanProximityDiscoveryTest {

    @Test
    fun twoDiscoveriesFindEachOther() {
        val crypto = JavaCrypto()
        val identityA = Identity.generate(crypto, "Android")
        val identityW = Identity.generate(crypto, "Windows")

        val discoveryA = LanProximityDiscovery()
        val discoveryW = LanProximityDiscovery()
        var aFoundW = false
        var wFoundA = false

        discoveryA.startAdvertising(ProximityProfile(identityA.identity.deviceId, "Android"))
        discoveryW.startAdvertising(ProximityProfile(identityW.identity.deviceId, "Windows"))
        discoveryA.startScanning(object : ProximityListener {
            override fun onPeerFound(endpoint: ProximityEndpoint) {
                if (endpoint.deviceId == identityW.identity.deviceId) aFoundW = true
            }
        })
        discoveryW.startScanning(object : ProximityListener {
            override fun onPeerFound(endpoint: ProximityEndpoint) {
                if (endpoint.deviceId == identityA.identity.deviceId) wFoundA = true
            }
        })

        try {
            assumeTrue(
                "mDNS unavailable on this host: ${discoveryA.error ?: discoveryW.error}",
                discoveryA.error == null && discoveryW.error == null
            )

            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                if (aFoundW && wFoundA) break
                Thread.sleep(100)
            }

            assertTrue(aFoundW, "A should find W via proximity mDNS")
            assertTrue(wFoundA, "W should find A via proximity mDNS")
            assertTrue(
                discoveryA.nearby().any { it.deviceId == identityW.identity.deviceId },
                "A's nearby() should list W"
            )
            assertTrue(
                discoveryW.nearby().any { it.deviceId == identityA.identity.deviceId },
                "W's nearby() should list A"
            )
            // Neither discovery may report itself.
            assertTrue(
                discoveryA.nearby().none { it.deviceId == identityA.identity.deviceId },
                "A must not discover itself"
            )
        } finally {
            discoveryA.stop()
            discoveryW.stop()
        }
    }
}
