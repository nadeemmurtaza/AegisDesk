package com.newax.aegis.sync

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end LAN transport tests: real sockets on loopback, real JDK crypto
 * (Ed25519/X25519/AES-GCM), the full SessionCrypto handshake, and the S1
 * engine driven over the wire. mDNS discovery exercises real JmDNS and is
 * skipped (assumption, not failure) on hosts without multicast.
 */
class LanTransportTest {

    // ── fixtures ──────────────────────────────────────────────────────────────

    private class World {
        val crypto = JavaCrypto()

        val identityA = Identity.generate(crypto, "Android")
        val identityW = Identity.generate(crypto, "Windows")
        val identityM = Identity.generate(crypto, "macOS")

        val keyStoreA = InMemoryKeyStore().apply {
            saveIdentity(identityA)
            savePeer(peerOf(identityW, "Windows"))
            savePeer(peerOf(identityM, "macOS"))
        }
        val keyStoreW = InMemoryKeyStore().apply {
            saveIdentity(identityW)
            savePeer(peerOf(identityA, "Android"))
            savePeer(peerOf(identityM, "macOS"))
        }
        val keyStoreM = InMemoryKeyStore().apply {
            saveIdentity(identityM)
            savePeer(peerOf(identityA, "Android"))
            savePeer(peerOf(identityW, "Windows"))
        }

        fun storeA(deviceId: String = identityA.identity.deviceId) = InMemoryJournalStore(deviceId)
        fun storeW(deviceId: String = identityW.identity.deviceId) = InMemoryJournalStore(deviceId)
        fun storeM(deviceId: String = identityM.identity.deviceId) = InMemoryJournalStore(deviceId)

        fun peerOf(identity: StoredIdentity, displayName: String): PairedPeer = PairedPeer(
            deviceId = identity.identity.deviceId,
            displayName = displayName,
            signPublicKey = identity.identity.signPublicKey,
            ecdhPublicKey = identity.identity.ecdhPublicKey,
            pairedAtMs = 1_000_000L
        )

        fun endpointFor(identity: StoredIdentity, port: Int): PeerEndpoint = PeerEndpoint(
            deviceId = identity.identity.deviceId,
            displayName = identity.identity.displayName,
            host = "127.0.0.1",
            port = port
        )
    }

    private fun entry(opId: String, deviceId: String, wall: Long, table: String, key: String): SyncEntry =
        SyncEntry.of(opId = opId, deviceId = deviceId, hlc = Hlc(wall, 0), table = table, key = key)

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun loopbackHandshakeAndSealedMessage() {
        val w = World()
        val replies = java.util.concurrent.LinkedBlockingQueue<WireCodec.SyncMessage>()
        var inboundPeerId: String? = null

        val transportW = JvmLanTransport(w.identityW, w.keyStoreW, w.crypto, port = 0)
        transportW.start(object : TransportListener {
            override fun onPeerDiscovered(endpoint: PeerEndpoint) = Unit
            override fun onPeerConnected(connection: TransportConnection) {
                inboundPeerId = connection.peerDeviceId
                val msg = connection.receive(5_000)
                if (msg != null) {
                    replies.add(msg)
                    connection.send(WireCodec.SyncMessage.AckVector(connection.peerDeviceId, Hlc(7, 3)))
                }
            }
        })
        try {
            val transportA = JvmLanTransport(w.identityA, w.keyStoreA, w.crypto, port = 0)
            val conn = transportA.connect(w.endpointFor(w.identityW, transportW.boundPort))
            assertNotNull(conn, "handshake should succeed between paired peers")
            assertEquals(w.identityW.identity.deviceId, conn.peerDeviceId)

            assertTrue(
                conn.send(WireCodec.SyncMessage.VectorExchange(w.identityA.identity.deviceId, emptyMap())),
                "sealed send should succeed"
            )
            val reply = conn.receive(5_000)
            assertNotNull(reply, "peer should answer with a sealed message")
            assertIs<WireCodec.SyncMessage.AckVector>(reply)
            assertEquals(Hlc(7, 3), reply.ackedHlc)

            // The inbound side saw the handshake-pinned peer id, not a caller-supplied one.
            assertEquals(w.identityA.identity.deviceId, inboundPeerId)

            // Reply received on W's side proves the sealed message travelled both ways.
            val inbound = replies.poll(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertNotNull(inbound, "W should have received A's sealed message")
            assertIs<WireCodec.SyncMessage.VectorExchange>(inbound)

            conn.close()
        } finally {
            transportW.stop()
        }
    }

    @Test
    fun unpairedPeerRejected() {
        val w = World()
        // W's keystore deliberately does NOT contain A.
        val keyStoreWUnpaired = InMemoryKeyStore().apply {
            saveIdentity(w.identityW)
            savePeer(w.peerOf(w.identityM, "macOS"))
        }
        val transportW = JvmLanTransport(w.identityW, keyStoreWUnpaired, w.crypto, port = 0)
        transportW.start(object : TransportListener {
            override fun onPeerDiscovered(endpoint: PeerEndpoint) = Unit
            override fun onPeerConnected(connection: TransportConnection) = Unit
        })
        try {
            val transportA = JvmLanTransport(w.identityA, w.keyStoreA, w.crypto, port = 0)
            val conn = transportA.connect(w.endpointFor(w.identityW, transportW.boundPort))
            assertNull(conn, "an unpaired device must be rejected at the handshake")
        } finally {
            transportW.stop()
        }
    }

    @Test
    fun antiEntropyConvergesOverTheWire() {
        val w = World()
        // A and W start with one shared entry plus private divergences — the
        // classic W→M→I→A mesh scenario reduced to two live peers.
        val shared = entry("op0", "dev-base", 1, "persons", "p0")
        val aOnly = listOf(
            entry("op1", w.identityW.identity.deviceId, 2, "memory_records", "m1"),
            entry("op2", w.identityW.identity.deviceId, 3, "triples", "t1")
        )
        val wOnly = listOf(
            entry("op3", w.identityM.identity.deviceId, 4, "persons", "p1"),
            entry("op4", w.identityM.identity.deviceId, 5, "ui_procedures", "u1")
        )
        val storeA = w.storeA()
        val storeW = w.storeW()
        storeA.append(listOf(shared) + aOnly)
        storeW.append(listOf(shared) + wOnly)

        val transportW = JvmLanTransport(w.identityW, w.keyStoreW, w.crypto, port = 0)
        transportW.start(AntiEntropyRunner.syncListenerFor(storeW))
        try {
            val transportA = JvmLanTransport(w.identityA, w.keyStoreA, w.crypto, port = 0)
            val conn = transportA.connect(w.endpointFor(w.identityW, transportW.boundPort))
            assertNotNull(conn)

            val resultA = AntiEntropyRunner.syncOnce(conn, storeA, timeoutMs = 5_000)
            assertTrue(resultA.completed, "A's round should complete, got ${resultA.outcome}")
            assertEquals(2, resultA.receivedEntries, "A should receive W's two private entries")
            assertEquals(1, resultA.duplicates, "A's shared entry is already present")

            // W's inbound round runs on its accept thread — poll for convergence.
            val expected = setOf("op0", "op1", "op2", "op3", "op4")
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (storeA.existingOpIds() == expected && storeW.existingOpIds() == expected) break
                Thread.sleep(50)
            }

            assertEquals(expected, storeA.existingOpIds(), "A must converge to the union")
            assertEquals(expected, storeW.existingOpIds(), "W must converge to the union")
            assertTrue(
                storeW.watermarkFor(w.identityA.identity.deviceId) > Hlc.ZERO,
                "W must have advanced its watermark for A"
            )

            conn.close()
        } finally {
            transportW.stop()
        }
    }

    @Test
    fun mdnsDiscoversRegisteredPeer() {
        val w = World()
        var aFoundW = false
        var wFoundA = false

        val transportW = JvmLanTransport(w.identityW, w.keyStoreW, w.crypto, port = 0)
        transportW.start(object : TransportListener {
            override fun onPeerDiscovered(endpoint: PeerEndpoint) {
                if (endpoint.deviceId == w.identityA.identity.deviceId) wFoundA = true
            }
            override fun onPeerConnected(connection: TransportConnection) = Unit
        })
        try {
            val transportA = JvmLanTransport(w.identityA, w.keyStoreA, w.crypto, port = 0)
            transportA.start(object : TransportListener {
                override fun onPeerDiscovered(endpoint: PeerEndpoint) {
                    if (endpoint.deviceId == w.identityW.identity.deviceId) aFoundW = true
                }
                override fun onPeerConnected(connection: TransportConnection) = Unit
            })
            try {
                // mDNS init is synchronous in start() — if the host lacks
                // multicast, skip instead of failing (real discovery is
                // machine-run per the design).
                assumeTrue(
                    "mDNS unavailable on this host: ${transportA.mdnsError ?: transportW.mdnsError}",
                    transportA.mdnsError == null && transportW.mdnsError == null
                )

                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline) {
                    if (aFoundW && wFoundA) break
                    Thread.sleep(100)
                }

                assertTrue(aFoundW, "A should discover W via mDNS")
                assertTrue(wFoundA, "W should discover A via mDNS")
                assertTrue(
                    transportA.discoveredPeers().any { it.deviceId == w.identityW.identity.deviceId },
                    "A's discoveredPeers() should list W"
                )
                assertTrue(
                    transportW.discoveredPeers().any { it.deviceId == w.identityA.identity.deviceId },
                    "W's discoveredPeers() should list A"
                )
            } finally {
                transportA.stop()
            }
        } finally {
            transportW.stop()
        }
    }
}
