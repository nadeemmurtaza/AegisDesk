package com.newax.aegis.sync

/**
 * Minimal WebSocket seam for the relay path (docs/SYNC_DESIGN.md §10).
 *
 * The relay transport only needs three things from a WebSocket stack:
 * connect, send one complete binary message, receive binary messages
 * (possibly fragmented), and a close/error signal. `java.net.http.WebSocket`
 * (the JVM actual) and OkHttp (the Android actual) both provide exactly that,
 * behind one platform-free interface so the relay's demux/handshake/sealing
 * code lives in jvmAndroidMain and compiles for both targets.
 *
 * The `last` flag on [WsListener.onBinary] mirrors the RFC 6455 FIN bit:
 * a client may deliver whole messages (`last = true` always, OkHttp) or
 * fragments (`last = false` until the final one, java.net.http) — the
 * consumer assembles.
 */
interface WsConnection {
    /** Send one complete binary message; false when the socket is gone. */
    fun sendBinary(bytes: ByteArray): Boolean

    /** Graceful close (the relay path treats close/error as session teardown). */
    fun close()
}

interface WsListener {
    fun onOpen()
    fun onBinary(bytes: ByteArray, last: Boolean)
    fun onError(error: Throwable)
    fun onClose()
}

interface WsClient {
    /** Connect; null when the platform stack cannot be built (never thrown for I/O). */
    fun connect(url: String, listener: WsListener): WsConnection?
}

/** Platform actuals: jvmMain = java.net.http, androidMain = OkHttp. */
expect fun platformWsClient(): WsClient
