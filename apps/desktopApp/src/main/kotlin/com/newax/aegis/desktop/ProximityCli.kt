package com.newax.aegis.desktop

import com.newax.aegis.sync.Identity
import com.newax.aegis.sync.KeyPair
import com.newax.aegis.sync.ProximityFiles
import com.newax.aegis.sync.ProximityHandshake
import com.newax.aegis.sync.ProximityListener
import com.newax.aegis.sync.ProximityEndpoint
import com.newax.aegis.sync.ProximityProfile
import com.newax.aegis.sync.ProximityTransfer
import com.newax.aegis.sync.StoredIdentity
import com.newax.aegis.sync.TcpTransferChannel
import com.newax.aegis.sync.platformCrypto
import com.newax.aegis.sync.platformKeyStore
import com.newax.aegis.sync.proximityDiscovery
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The desktop Quick Share surface (docs/SYNC_DESIGN.md §10.1 / P2) — the CLI
 * twin of Android's NearbyShareScreen: mDNS discovery
 * ([proximityDiscovery] → LanProximityDiscovery), direct TCP transfer
 * ([TcpTransferChannel]), the shared ECDH key exchange + [ProximityTransfer]
 * protocol, and the same accept/reject confirmation gate. Received files
 * land in `~/.aegis/shared/`.
 */
object ProximityCli {

    private const val RECEIVE_DIR = "shared"

    fun identity(): StoredIdentity {
        platformKeyStore().loadIdentity()?.let { return it }
        val created = Identity.generate(platformCrypto(), "Desktop " + System.getProperty("os.name"))
        platformKeyStore().saveIdentity(created)
        return created
    }

    /** `proximity nearby` — list devices on the LAN for 10 s. */
    fun nearby() {
        val discovery = proximityDiscovery()
        discovery.startScanning(object : ProximityListener {
            override fun onPeerFound(endpoint: ProximityEndpoint) {
                val at = "${endpoint.address ?: "?"}:${endpoint.port ?: "?"}"
                println("    found ${endpoint.displayName} (${endpoint.deviceId}) @ $at")
            }
        })
        discovery.error?.let { println("    (discovery degraded: $it)") }
        println("  Scanning for nearby devices… (10 s)")
        Thread.sleep(10_000)
        discovery.stop()
    }

    /** `proximity listen` — advertise + accept encrypted transfers forever. */
    fun listen() {
        val identity = identity()
        val discovery = proximityDiscovery()
        val server = ServerSocket(0)
        val port = server.localPort
        discovery.startAdvertising(ProximityProfile(identity.identity.deviceId, identity.identity.displayName, port))
        discovery.startScanning(object : ProximityListener {
            override fun onPeerFound(endpoint: ProximityEndpoint) {
                if (endpoint.deviceId != identity.identity.deviceId) {
                    println("    peer visible: ${endpoint.displayName} (${endpoint.deviceId})")
                }
            }
        })
        println("  Listening as ${identity.identity.displayName} (${identity.identity.deviceId}) on port $port")
        println("  Waiting for encrypted transfers… (Ctrl+C to stop)")
        while (!server.isClosed) {
            val tcp = TcpTransferChannel.accept(server, 30_000) ?: continue
            Thread { receiveOne(tcp, identity) }.apply {
                isDaemon = true
                name = "aegis-proximity-receive"
                start()
            }
        }
        discovery.stop()
    }

    /** `proximity send <file> <deviceId>` — discover, connect, encrypt, send. */
    fun send(filePath: String, deviceId: String) {
        val file = File(filePath)
        if (!file.isFile) {
            println("  No such file: $filePath")
            return
        }
        val content = file.readBytes()
        val identity = identity()
        val discovery = proximityDiscovery()
        println("  Looking for $deviceId on the LAN…")
        var endpoint: ProximityEndpoint? = null
        val found = CountDownLatch(1)
        discovery.startScanning(object : ProximityListener {
            override fun onPeerFound(e: ProximityEndpoint) {
                if (e.deviceId == deviceId && endpoint == null) {
                    endpoint = e
                    found.countDown()
                }
            }
        })
        val ok = try {
            found.await(15, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        discovery.stop()
        val target = endpoint
        if (!ok || target == null) {
            println("  Peer $deviceId not found nearby. Is it running \"proximity listen\" on the same network?")
            return
        }
        val address = target.address
        val port = target.port
        if (address == null || port == null) {
            println("  Peer advertises no transfer address — cannot connect.")
            return
        }
        println("  Found ${target.displayName} @ $address:$port")
        val tcp = TcpTransferChannel.connect(address, port)
        if (tcp == null) {
            println("  Could not connect to $address:$port")
            return
        }
        val crypto = platformCrypto()
        val ecdh = KeyPair(identity.ecdhPrivateKey, identity.identity.ecdhPublicKey)
        val peerKey = ProximityHandshake.exchangeKeys(tcp, ecdh.publicKey)
        if (peerKey == null) {
            println("  Key exchange failed — aborting.")
            tcp.close()
            return
        }
        println("  Sending ${file.name} (${file.length()} bytes)…")
        val result = ProximityTransfer.send(
            crypto, tcp, ecdh, peerKey, identity.identity.deviceId, file.name, content,
            object : ProximityTransfer.Progress {
                override fun onChunk(index: Int, of: Int) {
                    print("\r    chunk ${index + 1}/$of")
                    System.out.flush()
                }
            }
        )
        tcp.close()
        println()
        when (result) {
            is ProximityTransfer.Result.Sent ->
                println("  ✓ Sent — SHA-256 ${result.sha256Hex}")
            is ProximityTransfer.Result.Failed ->
                println("  ✗ Failed at ${result.stage}: ${result.reason}")
            is ProximityTransfer.Result.Received ->
                println("  Unexpected: received instead of sent")
        }
    }

    private fun receiveOne(tcp: TcpTransferChannel, identity: StoredIdentity) {
        val crypto = platformCrypto()
        val ecdh = KeyPair(identity.ecdhPrivateKey, identity.identity.ecdhPublicKey)
        val peerKey = ProximityHandshake.exchangeKeys(tcp, ecdh.publicKey)
        if (peerKey == null) {
            println("  [receive] key exchange failed — connection dropped.")
            tcp.close()
            return
        }
        val result = ProximityTransfer.receive(
            crypto, tcp, ecdh, identity.identity.deviceId,
            accept = { meta ->
                print("  Accept ${meta.fileName} (${meta.sizeBytes} bytes) from ${meta.senderDeviceId}? [y/N] ")
                System.out.flush()
                val accepted = readLine()?.trim()?.let { it == "y" || it == "yes" } == true
                println(if (accepted) "  accepted" else "  declined")
                accepted
            },
            progress = object : ProximityTransfer.Progress {
                override fun onChunk(index: Int, of: Int) {
                    print("\r    receiving chunk ${index + 1}/$of")
                    System.out.flush()
                }
            }
        )
        tcp.close()
        println()
        when (result) {
            is ProximityTransfer.Result.Received -> {
                val dir = File(System.getProperty("user.home"), ".aegis/$RECEIVE_DIR").apply { mkdirs() }
                val file = File(dir, ProximityFiles.safeName(result.fileName))
                file.writeBytes(result.content)
                println("  ✓ Received ${result.fileName} (${result.content.size} bytes) → ${file.absolutePath}")
                println("    SHA-256 ${result.sha256Hex}")
            }
            is ProximityTransfer.Result.Failed ->
                println("  ✗ Failed at ${result.stage}: ${result.reason}")
            is ProximityTransfer.Result.Sent ->
                println("  Unexpected: sent instead of received")
        }
    }
}
