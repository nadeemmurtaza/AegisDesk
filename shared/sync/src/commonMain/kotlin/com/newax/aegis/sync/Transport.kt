package com.newax.aegis.sync

/**
 * The transport contract (docs/SYNC_DESIGN.md §2, §9) — platform-free, so the
 * Android and iOS adapters can follow the same interface as the JVM LAN
 * implementation in jvmMain. The contract knows nothing about sockets: it
 * deals in [PeerEndpoint]s, [TransportConnection]s, and the WireCodec message
 * model. Sealing is a transport concern — the implementation runs the
 * SessionCrypto handshake (S2) and seals every app message; callers only ever
 * see plaintext [WireCodec.SyncMessage]s.
 */
data class PeerEndpoint(
    /** The peer's fingerprint-derived identity (must match a PairedPeer to connect). */
    val deviceId: String,
    val displayName: String,
    /** Hostname or literal IP for the direct connection. */
    val host: String,
    val port: Int
)

/**
 * One established, mutually-authenticated session to a paired peer.
 * [send]/[receive] are synchronous: send returns false when the write failed,
 * receive blocks up to [timeoutMs] and returns null on timeout, EOF, or any
 * frame that fails AEAD/codec validation (R9 — named failure modes, never a
 * crash). Both directions are safe to call from different threads.
 */
interface TransportConnection {
    /** The paired peer's deviceId — pinned by the handshake, never caller-supplied. */
    val peerDeviceId: String

    fun send(message: WireCodec.SyncMessage): Boolean

    fun receive(timeoutMs: Long): WireCodec.SyncMessage?

    fun close()
}

/** Receives transport events on the transport's own threads — keep handlers short. */
interface TransportListener {

    /** A peer announced itself via discovery (mDNS). Connect at will. */
    fun onPeerDiscovered(endpoint: PeerEndpoint)

    /** An inbound connection survived the handshake; run the sync round here. */
    fun onPeerConnected(connection: TransportConnection)
}

/**
 * A sync transport: advertises this device, discovers paired peers, and opens
 * [TransportConnection]s. Implementations own their background threads
 * (accept loop, discovery); [stop] must release them all.
 */
interface SyncTransport {

    fun start(listener: TransportListener)

    fun stop()

    /**
     * Open a session to a discovered peer. Null when the peer is not paired,
     * unreachable, or the mutual handshake fails — never throws.
     */
    fun connect(endpoint: PeerEndpoint): TransportConnection?

    /** Peers currently visible to discovery, newest last. */
    fun discoveredPeers(): List<PeerEndpoint>
}
