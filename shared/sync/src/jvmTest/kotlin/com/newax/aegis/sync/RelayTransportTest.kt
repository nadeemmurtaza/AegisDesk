package com.newax.aegis.sync

import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end relay test: spawns the real Node relay (relay/server.js), runs
 * the full stack — RelayTransport → SessionCrypto handshake → sealed frames →
 * relay routing → responder accept → anti-entropy round — and asserts the two
 * journals converge exactly as they do on the LAN path. Skipped (assumption,
 * not failure) when node or the ws dependency is unavailable; the server-side
 * suite is `cd relay && npm test`.
 */
class RelayTransportTest {

    @Test
    fun relayConvergenceEndToEnd() {
        val relay = when (val launch = NodeRelay.start()) {
            is NodeRelay.Launch.Unavailable -> {
                assumeTrue(launch.reason, false)
                return
            }
            is NodeRelay.Launch.Failed -> {
                assumeTrue(launch.reason, false)
                return
            }
            is NodeRelay.Launch.Running -> launch.relay
        }
        try {
            val world = SyncTestWorld()
            val storeA = world.store(world.identityA)
            val storeW = world.store(world.identityW)
            val aOnly = listOf(
                entry("op1", world.identityA.identity.deviceId, 2, "memory_records", "m1"),
                entry("op2", world.identityA.identity.deviceId, 3, "triples", "t1")
            )
            val wOnly = listOf(
                entry("op3", world.identityW.identity.deviceId, 4, "persons", "p1"),
                entry("op4", world.identityW.identity.deviceId, 5, "ui_procedures", "u1")
            )
            storeA.append(aOnly)
            storeW.append(wOnly)

            val transportA = RelayTransport(world.identityA, world.keyStoreA, world.crypto, relay.url, handshakeTimeoutMs = 5_000)
            val transportW = RelayTransport(world.identityW, world.keyStoreW, world.crypto, relay.url, handshakeTimeoutMs = 5_000)
            transportA.start(AntiEntropyRunner.syncListenerFor(storeA))
            transportW.start(AntiEntropyRunner.syncListenerFor(storeW))
            try {
                // W registered first → A's registration triggers W's presence.
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    if (transportW.discoveredPeers().any { it.deviceId == world.identityA.identity.deviceId }) break
                    Thread.sleep(50)
                }
                assertTrue(
                    transportW.discoveredPeers().any { it.deviceId == world.identityA.identity.deviceId },
                    "W should see A's presence through the relay"
                )

                // A initiates a sync round through the relay (host/port are unused).
                val conn = transportA.connect(PeerEndpoint(world.identityW.identity.deviceId, "Windows", host = "", port = 0))
                assertNotNull(conn, "relay handshake should succeed between paired peers")
                assertEquals(world.identityW.identity.deviceId, conn.peerDeviceId)

                val result = AntiEntropyRunner.syncOnce(conn, storeA, timeoutMs = 5_000)
                assertTrue(result.completed, "A's relayed round should complete, got ${result.outcome}")
                assertEquals(2, result.receivedEntries, "A should receive W's two entries")

                // W's inbound round runs on its accept thread — poll for convergence.
                val expected = setOf("op1", "op2", "op3", "op4")
                val convergence = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < convergence) {
                    if (storeA.existingOpIds() == expected && storeW.existingOpIds() == expected) break
                    Thread.sleep(50)
                }
                assertEquals(expected, storeA.existingOpIds(), "A must converge to the union through the relay")
                assertEquals(expected, storeW.existingOpIds(), "W must converge to the union through the relay")

                conn.close()
            } finally {
                transportA.stop()
                transportW.stop()
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun relayRejectsUnpairedInitiator() {
        val relay = when (val launch = NodeRelay.start()) {
            is NodeRelay.Launch.Unavailable -> {
                assumeTrue(launch.reason, false)
                return
            }
            is NodeRelay.Launch.Failed -> {
                assumeTrue(launch.reason, false)
                return
            }
            is NodeRelay.Launch.Running -> launch.relay
        }
        try {
            val world = SyncTestWorld()
            // A does NOT pair with C (C's frame must be dropped by the relay's
            // pair-aware routing: A never granted C, so no HelloR ever returns).
            val keyStoreC = InMemoryKeyStore().apply {
                saveIdentity(world.identityC)
                savePeer(world.peerOf(world.identityA, "Android"))
            }
            val transportA = RelayTransport(world.identityA, world.keyStoreA, world.crypto, relay.url, handshakeTimeoutMs = 5_000)
            val transportC = RelayTransport(world.identityC, keyStoreC, world.crypto, relay.url, handshakeTimeoutMs = 5_000)
            transportA.start(object : TransportListener {
                override fun onPeerDiscovered(endpoint: PeerEndpoint) = Unit
                override fun onPeerConnected(connection: TransportConnection) = Unit
            })
            transportC.start(object : TransportListener {
                override fun onPeerDiscovered(endpoint: PeerEndpoint) = Unit
                override fun onPeerConnected(connection: TransportConnection) = Unit
            })
            try {
                val conn = transportC.connect(PeerEndpoint(world.identityA.identity.deviceId, "Android", host = "", port = 0))
                assertNull(conn, "the relay must not route an un-granted sender's handshake")
            } finally {
                transportA.stop()
                transportC.stop()
            }
        } finally {
            relay.stop()
        }
    }

    private fun entry(opId: String, deviceId: String, wall: Long, table: String, key: String): SyncEntry =
        SyncEntry.of(opId = opId, deviceId = deviceId, hlc = Hlc(wall, 0), table = table, key = key)
}

/** Shared fixtures for the JVM transport tests (LAN + relay). */
internal class SyncTestWorld {
    val crypto = JavaCrypto()
    val identityA = Identity.generate(crypto, "Android")
    val identityW = Identity.generate(crypto, "Windows")
    val identityC = Identity.generate(crypto, "Chromebook")

    val keyStoreA = InMemoryKeyStore().apply {
        saveIdentity(identityA)
        savePeer(peerOf(identityW, "Windows"))
    }
    val keyStoreW = InMemoryKeyStore().apply {
        saveIdentity(identityW)
        savePeer(peerOf(identityA, "Android"))
    }

    fun store(identity: StoredIdentity) = InMemoryJournalStore(identity.identity.deviceId)

    fun peerOf(identity: StoredIdentity, displayName: String): PairedPeer = PairedPeer(
        deviceId = identity.identity.deviceId,
        displayName = displayName,
        signPublicKey = identity.identity.signPublicKey,
        ecdhPublicKey = identity.identity.ecdhPublicKey,
        pairedAtMs = 1_000_000L
    )
}

/** Spawns the real Node relay for a test, guarded: skips when node/ws absent. */
internal class NodeRelay private constructor(
    private val proc: Process,
    private val port: Int
) {
    val url: String get() = "ws://127.0.0.1:$port"

    fun stop() {
        try {
            proc.destroy()
        } catch (_: Exception) {
        }
    }

    /** Launch outcome of [start] — declared on the class so `NodeRelay.Launch.X` resolves as a type path. */
    sealed interface Launch {
        data class Running(val relay: NodeRelay) : Launch
        data class Failed(val reason: String) : Launch
        data class Unavailable(val reason: String) : Launch
    }

    companion object {

        fun start(): Launch {
            val node = findExecutable("node") ?: return Launch.Unavailable("node not on PATH")
            val version = runCapture(node, listOf("--version"))?.trim()
                ?: return Launch.Unavailable("node --version failed")
            val major = version.removePrefix("v").substringBefore(".").toIntOrNull()
                ?: return Launch.Unavailable("unparseable node version: $version")
            if (major < 18) return Launch.Unavailable("node >= 18 required (node --test), got $version")

            val serverJs = findRelayServer() ?: return Launch.Unavailable("relay/server.js not found")
            val port = freePort() ?: return Launch.Unavailable("no free port")

            val proc = try {
                ProcessBuilder(node, serverJs, "--port", port.toString())
                    .redirectErrorStream(true)
                    .start()
            } catch (e: IOException) {
                return Launch.Failed("could not spawn node relay: ${e.message}")
            }

            if (!waitForHealth(port, 10_000)) {
                val output = drain(proc.inputStream)
                proc.destroy()
                return Launch.Failed("node relay did not become healthy — run 'cd relay && npm install && npm test'. Output: $output")
            }
            return Launch.Running(NodeRelay(proc, port))
        }

        private fun findExecutable(name: String): String? {
            val exts =
                if (System.getProperty("os.name").lowercase().contains("win")) listOf("", ".exe", ".cmd") else listOf("")
            val path = System.getenv("PATH") ?: return null
            for (dir in path.split(File.pathSeparator)) {
                for (ext in exts) {
                    val candidate = File(dir, name + ext)
                    if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
                }
            }
            return null
        }

        private fun findRelayServer(): String? {
            var dir = File(System.getProperty("user.dir"))
            while (dir != null) {
                val candidate = File(dir, "relay/server.js")
                if (candidate.isFile) return candidate.absolutePath
                dir = dir.parentFile
            }
            return null
        }

        private fun freePort(): Int? = try {
            ServerSocket(0).use { it.localPort }
        } catch (e: IOException) {
            null
        }

        private fun runCapture(command: String, args: List<String>): String? = try {
            ProcessBuilder(listOf(command) + args).redirectErrorStream(true).start().let { p ->
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                out
            }
        } catch (e: IOException) {
            null
        }

        private fun drain(stream: java.io.InputStream): String = try {
            stream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }

        private fun waitForHealth(port: Int, timeoutMs: Long): Boolean {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val res = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/health"))
                            .timeout(Duration.ofMillis(800)).GET().build(),
                        HttpResponse.BodyHandlers.discarding()
                    )
                    if (res.statusCode() == 200) return true
                } catch (_: Exception) {
                    // relay not up yet — retry
                }
                Thread.sleep(100)
            }
            return false
        }
    }
}
