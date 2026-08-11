package com.newax.aegis.sync

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The framed-byte-channel seam shared by every JVM transport (LAN socket,
 * relay WebSocket, and later the WiFi-Direct socket). A [FrameChannel]
 * delivers whole [Frame]s — [Frames.KIND_SEALED] app messages and the
 * handshake frames — so the SessionCrypto handshake and the sealed session
 * are implemented ONCE and run identically over any transport
 * (docs/SYNC_DESIGN.md §9: "LAN direct and relayed paths use the same
 * crypto; the relay sees only ciphertext").
 */
internal interface FrameChannel {

    /** Next frame, blocking up to [timeoutMs]; null on timeout/EOF/closed. */
    fun read(timeoutMs: Long): Frame?

    /** One frame; false when the channel is closed or the write failed. */
    fun write(kind: Char, payload: ByteArray): Boolean

    fun close()
}

internal data class Frame(val kind: Char, val payload: ByteArray)

/** Wire-level frame kinds — shared by the LAN and relay transports. */
internal object Frames {
    const val KIND_HELLO_I = 'I'
    const val KIND_HELLO_R = 'R'
    const val KIND_FINISHED = 'F'
    const val KIND_SEALED = 'S'
    const val KIND_ERROR = 'E'

    /** Upper bound on a frame — deltas can carry large payloads. */
    const val MAX_FRAME_BYTES = 64 * 1024 * 1024
}

/** A TCP-socket frame channel (LAN, and later WiFi-Direct bulk transfer). */
internal class SocketFrameChannel(private val socket: Socket) : FrameChannel {

    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val writeLock = Any()

    override fun read(timeoutMs: Long): Frame? {
        return try {
            socket.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
            readRawFrame(socket, input)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    override fun write(kind: Char, payload: ByteArray): Boolean =
        writeRawFrame(socket, output, writeLock, kind, payload)

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

/**
 * The SessionCrypto XX handshake as a transport-agnostic state machine over a
 * [FrameChannel]. One implementation drives both the LAN socket and the relay
 * WebSocket: initiate sends HelloI, reads HelloR (rejecting an error frame),
 * proves key possession with Finished; accept verifies HelloI against the
 * paired peers in [keyStore] (rejecting unpaired devices), replies HelloR,
 * and demands the initiator's Finished proof.
 */
internal object SessionHandshake {

    /** Result of a completed responder handshake. */
    data class Accepted(val peerDeviceId: String, val session: SessionCrypto.Session)

    fun initiate(
        channel: FrameChannel,
        crypto: Crypto,
        identity: StoredIdentity,
        peer: PairedPeer,
        timeoutMs: Long
    ): SessionCrypto.Session? {
        val (eph, helloI) = SessionCrypto.initiatorHello(crypto, identity)
        if (!channel.write(Frames.KIND_HELLO_I, HandshakeWire.encodeHelloI(helloI))) return null
        val helloRFrame = channel.read(timeoutMs) ?: return null
        if (helloRFrame.kind == Frames.KIND_ERROR) return null // unpaired / rejected
        if (helloRFrame.kind != Frames.KIND_HELLO_R) return null
        val helloR = HandshakeWire.decodeHelloR(helloRFrame.payload) ?: return null
        val result = SessionCrypto.initiatorProcess(crypto, identity, peer, eph, helloI, helloR)
        if (result !is SessionCrypto.InitiatorResult.Success) return null
        val finished = SessionCrypto.buildFinished(crypto, result.session)
        if (!channel.write(Frames.KIND_FINISHED, HandshakeWire.encodeFinished(finished))) return null
        return result.session
    }

    fun accept(
        channel: FrameChannel,
        crypto: Crypto,
        identity: StoredIdentity,
        keyStore: KeyStore,
        timeoutMs: Long
    ): Accepted? {
        val helloIFrame = channel.read(timeoutMs) ?: return null
        if (helloIFrame.kind != Frames.KIND_HELLO_I) return null
        val helloI = HandshakeWire.decodeHelloI(helloIFrame.payload) ?: return null
        val peer = keyStore.pairedPeers().firstOrNull { it.deviceId == helloI.deviceId }
        if (peer == null) {
            channel.write(Frames.KIND_ERROR, "unpaired".encodeToByteArray())
            return null
        }
        val result = SessionCrypto.responderProcess(crypto, identity, peer, helloI)
        if (result !is SessionCrypto.ResponderResult.Success) {
            channel.write(Frames.KIND_ERROR, "handshake".encodeToByteArray())
            return null
        }
        if (!channel.write(Frames.KIND_HELLO_R, HandshakeWire.encodeHelloR(result.helloR))) return null
        val finishedFrame = channel.read(timeoutMs) ?: return null
        if (finishedFrame.kind != Frames.KIND_FINISHED) return null
        val finished = HandshakeWire.decodeFinished(finishedFrame.payload) ?: return null
        if (!SessionCrypto.verifyFinished(crypto, result.session, finished)) {
            channel.write(Frames.KIND_ERROR, "finished".encodeToByteArray())
            return null
        }
        return Accepted(peer.deviceId, result.session)
    }
}

/**
 * A mutually-authenticated, sealed session over any [FrameChannel]: every
 * [WireCodec.SyncMessage] is AEAD-sealed with the session key (transcript
 * pinned AAD, 8-byte replay-proof counters) before it touches the channel.
 * The single sealed-connection implementation shared by the LAN and relay
 * transports — callers only ever see plaintext messages.
 */
class SealedConnection internal constructor(
    private val channel: FrameChannel,
    private val crypto: Crypto,
    private val session: SessionCrypto.Session,
    override val peerDeviceId: String
) : TransportConnection {

    @Volatile
    private var closed = false

    override fun send(message: WireCodec.SyncMessage): Boolean {
        if (closed) return false
        val sealed = SessionCrypto.seal(crypto, session, WireCodec.encode(message).encodeToByteArray())
        return channel.write(Frames.KIND_SEALED, sealed)
    }

    override fun receive(timeoutMs: Long): WireCodec.SyncMessage? {
        if (closed) return null
        val frame = channel.read(timeoutMs) ?: return null
        if (frame.kind != Frames.KIND_SEALED) return null
        val plaintext = SessionCrypto.open(crypto, session, frame.payload) ?: return null
        return WireCodec.decode(plaintext.decodeToString())
    }

    override fun close() {
        closed = true
        channel.close()
    }
}

// ── frame protocol (socket) ──────────────────────────────────────────────────

private fun readRawFrame(socket: Socket, input: BufferedInputStream): Frame? {
    val header = ByteArray(5)
    var off = 0
    while (off < header.size) {
        val n = input.read(header, off, header.size - off)
        if (n < 0) return null
        off += n
    }
    val kind = header[0].toInt().toChar()
    val length = ((header[1].toInt() and 0xFF) shl 24) or
        ((header[2].toInt() and 0xFF) shl 16) or
        ((header[3].toInt() and 0xFF) shl 8) or
        (header[4].toInt() and 0xFF)
    if (length < 0 || length > Frames.MAX_FRAME_BYTES) return null
    val payload = ByteArray(length)
    off = 0
    while (off < length) {
        val n = input.read(payload, off, length - off)
        if (n < 0) return null
        off += n
    }
    return Frame(kind, payload)
}

private fun writeRawFrame(
    socket: Socket,
    output: BufferedOutputStream,
    lock: Any,
    kind: Char,
    payload: ByteArray
): Boolean = synchronized(lock) {
    try {
        output.write(kind.code)
        output.write(
            byteArrayOf(
                (payload.size ushr 24).toByte(),
                (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte()
            )
        )
        output.write(payload)
        output.flush()
        true
    } catch (_: IOException) {
        false
    }
}
