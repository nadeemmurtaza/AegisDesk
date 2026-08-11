package com.newax.aegis.sync

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * JVM [WsClient] actual over `java.net.http.WebSocket` — the exact behavior
 * the relay transport had before the seam (10s connect timeout, whole-message
 * delivery with the RFC 6455 FIN flag surfaced via [WsListener.onBinary]'s
 * `last`).
 */
class JvmWsClient : WsClient {

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun connect(url: String, listener: WsListener): WsConnection? {
        return try {
            val socket = client.newWebSocketBuilder()
                .buildAsync(URI.create(url), JvmWsListener(listener))
                .get(10, TimeUnit.SECONDS)
            JvmWsConnection(socket)
        } catch (e: Exception) {
            null
        }
    }

    private class JvmWsConnection(private val socket: WebSocket) : WsConnection {
        override fun sendBinary(bytes: ByteArray): Boolean = try {
            synchronized(socket) {
                socket.sendBinary(ByteBuffer.wrap(bytes), true).get(10, TimeUnit.SECONDS)
            }
            true
        } catch (_: Exception) {
            false
        }

        override fun close() {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
            } catch (_: Exception) {
            }
        }
    }

    private class JvmWsListener(private val delegate: WsListener) : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
            delegate.onOpen()
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            delegate.onBinary(bytes, last)
            webSocket.request(1)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            delegate.onError(error)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            delegate.onClose()
            return null
        }
    }
}
