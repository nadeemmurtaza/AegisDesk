package com.newax.aegis.sync

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Relay transport (docs/SYNC_DESIGN.md §10): the same framed, sealed session
 * protocol as the LAN path, routed through the E2E-blind WebSocket relay
 * (relay/server.js). The relay sees only device ids, routing grants, and
 * opaque frame payloads — the SessionCrypto handshake and AEAD-sealed
 * messages run device-to-device THROUGH it, unchanged ([SessionHandshake] +
 * [SealedConnection] over a queue-backed [FrameChannel]).
 *
 * Relay envelope (mirrors relay/protocol.js):
 *   [type:1][deviceId:utf8][0x00][payload]
 *   REG  me          | GRANT  me, payload=peer I allow | SEND  to, payload=frame
 *   ONLINE peer      | FORWARD from, payload=frame     | QUEUED to | ERROR reason
 *
 * Pair-aware routing: peers register GRANTs for every mesh-paired device, and
 * the relay forwards a frame only from a device the recipient has granted.
 * [connect] takes a [PeerEndpoint] whose host/port are unused — only
 * [PeerEndpoint.deviceId] matters through the relay.
 */
class RelayTransport(
    private val identity: StoredIdentity,
    private val keyStore: KeyStore,
    private val crypto: Crypto,
    private val relayUrl: String,
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS
) : SyncTransport {

    companion object {
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000L
    }

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** Peer → its inbound frame channel (established sessions and in-flight accepts). */
    private val channels = ConcurrentHashMap<String, RelayFrameChannel>()
    private val active = ConcurrentHashMap<String, SealedConnection>()
    private val discovered = CopyOnWriteArrayList<PeerEndpoint>()

    /** Peers this device is currently initiating a handshake towards. */
    private val initiating = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var ws: WebSocket? = null
    @Volatile
    private var listener: TransportListener? = null

    override fun start(listener: TransportListener) {
        this.listener = listener
        if (!relayUrl.startsWith("ws://") && !relayUrl.startsWith("wss://")) {
            throw IllegalArgumentException("relayUrl must be ws:// or wss://, got: $relayUrl")
        }
        val socket = try {
            client.newWebSocketBuilder()
                .buildAsync(URI.create(relayUrl), WsListener())
                .get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw IllegalStateException("relay unreachable: $relayUrl (${e.message})", e)
        }
        ws = socket
        // Register this device, then grant every paired peer the right to send to us.
        sendControl(RelayEnvelope.TYPE_REG, identity.identity.deviceId, ByteArray(0))
        keyStore.pairedPeers().forEach { peer ->
            sendControl(RelayEnvelope.TYPE_GRANT, identity.identity.deviceId, peer.deviceId.encodeToByteArray())
        }
    }

    override fun stop() {
        closeRelay()
        try {
            ws?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
        } catch (_: Exception) {
        }
        ws = null
    }

    override fun connect(endpoint: PeerEndpoint): TransportConnection? {
        val peer = keyStore.pairedPeers().firstOrNull { it.deviceId == endpoint.deviceId } ?: return null
        val channel = RelayFrameChannel { kind, payload ->
            sendControl(
                RelayEnvelope.TYPE_SEND,
                endpoint.deviceId,
                ByteArray(1) { kind.code.toByte() } + payload
            )
        }
        // Register before sending HelloI so the demux routes the reply into our queue.
        channels[endpoint.deviceId] = channel
        initiating.add(endpoint.deviceId)
        val session = try {
            SessionHandshake.initiate(channel, crypto, identity, peer, handshakeTimeoutMs)
        } finally {
            initiating.remove(endpoint.deviceId)
        }
        if (session == null) {
            channels.remove(endpoint.deviceId, channel)
            channel.close()
            return null
        }
        val connection = SealedConnection(channel, crypto, session, peer.deviceId)
        active[endpoint.deviceId] = connection
        return connection
    }

    override fun discoveredPeers(): List<PeerEndpoint> = discovered.toList()

    /** Re-send grants — call after pairing a new device while running. */
    fun grant(peerDeviceId: String) {
        sendControl(RelayEnvelope.TYPE_GRANT, identity.identity.deviceId, peerDeviceId.encodeToByteArray())
    }

    // ── inbound demux ─────────────────────────────────────────────────────────

    private fun handleMessage(message: ByteArray) {
        val env = RelayEnvelope.parse(message) ?: return
        when (env.type) {
            RelayEnvelope.TYPE_FORWARD -> {
                if (env.payload.isEmpty()) return
                val kind = env.payload[0].toInt().toChar()
                val frame = Frame(kind, env.payload.copyOfRange(1, env.payload.size))
                onForward(env.deviceId, frame)
            }
            RelayEnvelope.TYPE_ONLINE -> {
                if (env.deviceId == identity.identity.deviceId) return
                // Relay presence — the device is reachable for a sync round.
                val endpoint = PeerEndpoint(env.deviceId, env.deviceId, host = "", port = 0)
                discovered.add(endpoint)
                listener?.onPeerDiscovered(endpoint)
            }
            RelayEnvelope.TYPE_QUEUED -> Unit // informational — delivery surfaced at S5
            RelayEnvelope.TYPE_ERROR -> {
                // Server-level rejection (not-granted, frame-too-large) for this
                // peer — fail any in-flight handshake so connect() returns fast.
                channels.remove(env.deviceId)?.close()
            }
            else -> Unit
        }
    }

    private fun onForward(from: String, frame: Frame) {
        if (frame.kind == Frames.KIND_HELLO_I && initiating.contains(from)) {
            // Both sides initiated simultaneously (auto-sync on PRESENCE): their
            // HelloI wins — back off my initiation and complete as the responder
            // so the round still converges instead of failing on both sides.
            initiating.remove(from)
            channels.remove(from)?.close()
            startResponderAccept(from, frame)
            return
        }
        val channel = channels[from]
        if (channel != null) {
            channel.enqueue(frame)
            return
        }
        when (frame.kind) {
            Frames.KIND_HELLO_I -> startResponderAccept(from, frame)
            else -> Unit // unsolicited frame from an unknown/unpaired peer — ignore
        }
    }

    private fun startResponderAccept(from: String, helloI: Frame) {
        val channel = RelayFrameChannel { kind, payload ->
            sendControl(
                RelayEnvelope.TYPE_SEND,
                from,
                ByteArray(1) { kind.code.toByte() } + payload
            )
        }
        channel.enqueue(helloI)
        if (channels.putIfAbsent(from, channel) != null) return // a session already owns this peer
        Thread {
            val accepted = SessionHandshake.accept(channel, crypto, identity, keyStore, handshakeTimeoutMs)
            if (accepted != null) {
                val connection = SealedConnection(channel, crypto, accepted.session, accepted.peerDeviceId)
                active[from] = connection
                listener?.onPeerConnected(connection)
            } else {
                channels.remove(from, channel)
                channel.close()
            }
        }.apply {
            isDaemon = true
            name = "aegis-relay-accept"
            start()
        }
    }

    private fun closeRelay() {
        active.values.forEach { it.close() }
        active.clear()
        channels.values.forEach { it.close() }
        channels.clear()
    }

    // ── websocket plumbing ────────────────────────────────────────────────────

    private fun sendControl(type: Byte, deviceId: String, payload: ByteArray): Boolean {
        val socket = ws ?: return false
        return try {
            val envelope = RelayEnvelope.build(type, deviceId, payload)
            synchronized(socket) {
                socket.sendBinary(ByteBuffer.wrap(envelope), true).get(10, TimeUnit.SECONDS)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private inner class WsListener : WebSocket.Listener {
        private val fragment = ByteArrayOutputStream()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            fragment.write(bytes)
            if (last) {
                val message = fragment.toByteArray()
                fragment.reset()
                handleMessage(message)
            }
            webSocket.request(1)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            closeRelay()
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closeRelay()
            return null
        }
    }
}

/** Queue-backed [FrameChannel] for one peer through the relay. */
internal class RelayFrameChannel(
    private val outbound: (Char, ByteArray) -> Boolean
) : FrameChannel {

    private val inbound = LinkedBlockingQueue<Frame>()
    @Volatile
    private var closed = false

    /** Demux-side entry point — feeds a frame from the relay into this session. */
    fun enqueue(frame: Frame) {
        if (!closed) inbound.add(frame)
    }

    override fun read(timeoutMs: Long): Frame? {
        if (closed) return null
        return try {
            inbound.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }
    }

    override fun write(kind: Char, payload: ByteArray): Boolean {
        if (closed) return false
        return outbound(kind, payload)
    }

    override fun close() {
        closed = true
        inbound.clear()
    }
}

/** Relay envelope codec — mirrors relay/protocol.js; keep the shapes in lockstep. */
private object RelayEnvelope {

    const val TYPE_REG = 0x52.toByte()
    const val TYPE_GRANT = 0x47.toByte()
    const val TYPE_SEND = 0x44.toByte()
    const val TYPE_ONLINE = 0x4f.toByte()
    const val TYPE_FORWARD = 0x46.toByte()
    const val TYPE_QUEUED = 0x51.toByte()
    const val TYPE_ERROR = 0x45.toByte()

    private const val NUL = 0.toByte()

    fun build(type: Byte, deviceId: String, payload: ByteArray): ByteArray {
        val id = deviceId.encodeToByteArray()
        val out = ByteArray(1 + id.size + 1 + payload.size)
        out[0] = type
        id.copyInto(out, 1)
        out[1 + id.size] = NUL
        payload.copyInto(out, 2 + id.size)
        return out
    }

    fun parse(bytes: ByteArray): Envelope? {
        if (bytes.size < 2) return null
        val nul = bytes.indexOf(NUL, 1)
        if (nul < 0) return null
        return Envelope(
            type = bytes[0],
            deviceId = bytes.copyOfRange(1, nul).decodeToString(),
            payload = bytes.copyOfRange(nul + 1, bytes.size)
        )
    }

    data class Envelope(val type: Byte, val deviceId: String, val payload: ByteArray)
}
