package com.newax.aegis.sync

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * LAN transport for the JVM desktops (docs/SYNC_DESIGN.md §2): an mDNS
 * announcement + discovery of `_aegis-sync._tcp.local.` services, and a
 * ServerSocket accept loop that runs the SessionCrypto XX handshake on every
 * inbound connection (unpaired devices are rejected with an error frame).
 * [connect] opens the initiator side of the same handshake.
 *
 * mDNS failure is deliberately non-fatal: discovery degrades gracefully
 * ([mdnsError] exposes the reason) but direct [connect] to a known endpoint —
 * e.g. a manually-entered IP — keeps working. All threads are daemons owned
 * by this instance; [stop] releases them.
 */
class JvmLanTransport(
    private val identity: StoredIdentity,
    private val keyStore: KeyStore,
    private val crypto: Crypto,
    private val port: Int = DEFAULT_PORT,
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS
) : SyncTransport {

    companion object {
        const val DEFAULT_PORT = 42717
        const val SERVICE_TYPE = "_aegis-sync._tcp.local."

        /** Advertise props keys — kept stable so the resolver matches. */
        const val PROP_DEVICE_ID = "deviceId"
        const val PROP_DISPLAY_NAME = "displayName"

        private const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 10_000
    }

    private val discovered = CopyOnWriteArrayList<PeerEndpoint>()
    private val activeConnections = CopyOnWriteArrayList<TransportConnection>()
    private val closed = AtomicBoolean(true)

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var acceptThread: Thread? = null
    @Volatile
    private var mdns: JmDNS? = null
    @Volatile
    private var listener: TransportListener? = null

    /** Non-null when mDNS could not start — discovery is off, direct connect still works. */
    @Volatile
    var mdnsError: String? = null
        private set

    /** The port actually bound (differs from [port] when [port] is 0/ephemeral). */
    val boundPort: Int
        get() = serverSocket?.localPort ?: port

    override fun start(listener: TransportListener) {
        this.listener = listener
        closed.set(false)
        val server = ServerSocket(port)
        server.reuseAddress = true
        serverSocket = server
        acceptThread = Thread { acceptLoop(server) }.apply {
            isDaemon = true
            name = "aegis-sync-accept"
            start()
        }
        startMdns()
    }

    override fun stop() {
        closed.set(true)
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        try {
            mdns?.close()
        } catch (_: Exception) {
        }
        activeConnections.forEach { it.close() }
        activeConnections.clear()
        discovered.clear()
        acceptThread = null
    }

    override fun connect(endpoint: PeerEndpoint): TransportConnection? {
        val peer = keyStore.pairedPeers().firstOrNull { it.deviceId == endpoint.deviceId } ?: return null
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            val conn = JvmTransportConnection.initiate(socket, crypto, identity, peer, handshakeTimeoutMs)
            if (conn == null) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                null
            } else {
                activeConnections.add(conn)
                conn
            }
        } catch (_: IOException) {
            null
        }
    }

    override fun discoveredPeers(): List<PeerEndpoint> = discovered.toList()

    // ── accept loop ───────────────────────────────────────────────────────────

    private fun acceptLoop(server: ServerSocket) {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                if (closed.get()) return
                continue
            }
            Thread {
                val conn = JvmTransportConnection.accept(socket, crypto, identity, keyStore, handshakeTimeoutMs)
                if (conn != null) {
                    activeConnections.add(conn)
                    listener?.onPeerConnected(conn)
                }
            }.apply {
                isDaemon = true
                name = "aegis-sync-inbound"
                start()
            }
        }
    }

    // ── mDNS ──────────────────────────────────────────────────────────────────

    private fun startMdns() {
        try {
            val mdns = JmDNS.create()
            this.mdns = mdns
            val serviceName = "aegis-" + identity.identity.shortFingerprint
            val info = ServiceInfo.create(
                SERVICE_TYPE,
                serviceName,
                boundPort,
                0,
                0,
                mapOf(
                    PROP_DEVICE_ID to identity.identity.deviceId,
                    PROP_DISPLAY_NAME to identity.identity.displayName
                )
            )
            mdns.registerService(info)
            mdns.addServiceListener(SERVICE_TYPE, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    mdns.requestServiceInfo(SERVICE_TYPE, event.name, true)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    event.info?.let { info ->
                        deviceIdOf(info)?.let { deviceId ->
                            discovered.removeAll { it.deviceId == deviceId }
                        }
                    }
                }

                override fun serviceResolved(event: ServiceEvent) {
                    endpointOf(event.info)?.let { endpoint ->
                        if (endpoint.deviceId != identity.identity.deviceId) {
                            discovered.removeAll { it.deviceId == endpoint.deviceId }
                            discovered.add(endpoint)
                            listener?.onPeerDiscovered(endpoint)
                        }
                    }
                }
            })
            // Initial sweep on a background thread — mdns.list blocks until the
            // first responses arrive or the browse times out.
            Thread {
                try {
                    for (info in mdns.list(SERVICE_TYPE)) {
                        endpointOf(info)?.let { endpoint ->
                            if (endpoint.deviceId != identity.identity.deviceId) {
                                discovered.add(endpoint)
                                listener?.onPeerDiscovered(endpoint)
                            }
                        }
                    }
                } catch (_: IOException) {
                    // transient browse failure — live listener keeps working
                }
            }.apply {
                isDaemon = true
                name = "aegis-sync-mdns-sweep"
                start()
            }
        } catch (e: Exception) {
            // mDNS must never take the transport down — direct connect survives.
            mdnsError = "mDNS unavailable: ${e.message}"
        }
    }

    private fun deviceIdOf(info: ServiceInfo): String? =
        info.getPropertyString(PROP_DEVICE_ID)

    private fun endpointOf(info: ServiceInfo): PeerEndpoint? {
        val deviceId = deviceIdOf(info) ?: return null
        val displayName = info.getPropertyString(PROP_DISPLAY_NAME) ?: deviceId
        return PeerEndpoint(deviceId, displayName, info.hostAddress, info.port)
    }
}
