package com.newax.aegis.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Android [WsClient] actual over OkHttp — `java.net.http.WebSocket` does not
 * exist on Android, so the relay path uses the platform-standard WebSocket
 * client. OkHttp reassembles fragmented messages internally, so every
 * [WsListener.onBinary] delivery arrives whole (`last = true`).
 */
class OkHttpWsClient : WsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // keep the relay's idle sockets alive
        .build()

    override fun connect(url: String, listener: WsListener): WsConnection? {
        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                listener.onBinary(bytes.toByteArray(), true)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onError(t)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClose()
        })
        return OkHttpWsConnection(socket)
    }

    private class OkHttpWsConnection(private val socket: WebSocket) : WsConnection {
        override fun sendBinary(bytes: ByteArray): Boolean = try {
            socket.send(ByteString.of(*bytes))
        } catch (_: Exception) {
            false
        }

        override fun close() {
            try {
                socket.close(1000, null)
            } catch (_: Exception) {
            }
        }
    }
}

actual fun platformWsClient(): WsClient = OkHttpWsClient()
