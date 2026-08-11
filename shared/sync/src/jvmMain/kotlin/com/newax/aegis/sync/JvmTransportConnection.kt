package com.newax.aegis.sync

import java.io.IOException
import java.net.Socket

/**
 * LAN factories for the shared sealed-connection protocol: open a TCP socket,
 * run the SessionCrypto XX handshake over it (via [SessionHandshake]), and
 * hand back a [SealedConnection]. The relay transport (RelayTransport.kt)
 * produces the same [SealedConnection] over a WebSocket frame channel, so the
 * LAN and relayed paths share one handshake and one sealing implementation.
 */
object JvmTransportConnection {

    private const val HANDSHAKE_TIMEOUT_MS_DEFAULT = 30_000L

    /** Initiator side: returns the established connection, or null — never throws. */
    fun initiate(
        socket: Socket,
        crypto: Crypto,
        identity: StoredIdentity,
        peer: PairedPeer,
        timeoutMs: Long = HANDSHAKE_TIMEOUT_MS_DEFAULT
    ): SealedConnection? {
        val channel = SocketFrameChannel(socket)
        val session = SessionHandshake.initiate(channel, crypto, identity, peer, timeoutMs)
        if (session == null) {
            closeQuietly(socket)
            return null
        }
        return SealedConnection(channel, crypto, session, peer.deviceId)
    }

    /** Responder side: rejects unpaired devices; returns null on any failure. */
    fun accept(
        socket: Socket,
        crypto: Crypto,
        identity: StoredIdentity,
        keyStore: KeyStore,
        timeoutMs: Long = HANDSHAKE_TIMEOUT_MS_DEFAULT
    ): SealedConnection? {
        val channel = SocketFrameChannel(socket)
        val accepted = SessionHandshake.accept(channel, crypto, identity, keyStore, timeoutMs)
        if (accepted == null) {
            closeQuietly(socket)
            return null
        }
        return SealedConnection(channel, crypto, accepted.session, accepted.peerDeviceId)
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}
