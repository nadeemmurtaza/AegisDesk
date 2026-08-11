package com.newax.aegis.sync

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * [TransferChannel] over a plain TCP socket (4-byte big-endian length prefix
 * per message) — the Quick Share channel for the JVM desktops (LAN) and the
 * Android WiFi-Direct socket. Lives in jvmAndroidMain because java.net is
 * available on both compiled targets; one implementation, one wire format.
 *
 * [read] blocks up to [timeoutMs] and returns null on timeout/EOF/closed;
 * [write] returns false on failure. Neither throws (R9 — named failure
 * modes, never a crash).
 */
class TcpTransferChannel internal constructor(private val socket: Socket) : TransferChannel {

    companion object {

        /** Upper bound on one message — sealed blobs are hex, well under this. */
        const val MAX_MESSAGE_BYTES = 64 * 1024 * 1024

        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L

        /** Accept one inbound connection from [server]; null on timeout/closed. */
        fun accept(server: ServerSocket, timeoutMs: Long): TcpTransferChannel? {
            return try {
                server.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
                TcpTransferChannel(server.accept())
            } catch (_: SocketTimeoutException) {
                null
            } catch (_: IOException) {
                null
            }
        }

        /** Connect to [host]:[port]; null when the peer is unreachable. */
        fun connect(host: String, port: Int, connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS): TcpTransferChannel? {
            return try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs.toInt())
                TcpTransferChannel(socket)
            } catch (_: IOException) {
                null
            }
        }
    }

    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val writeLock = Any()

    override fun read(timeoutMs: Long): ByteArray? {
        return try {
            socket.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
            readMessage()
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    override fun write(message: ByteArray): Boolean {
        if (message.size > MAX_MESSAGE_BYTES) return false
        return synchronized(writeLock) {
            try {
                output.write(
                    byteArrayOf(
                        (message.size ushr 24).toByte(),
                        (message.size ushr 16).toByte(),
                        (message.size ushr 8).toByte(),
                        message.size.toByte()
                    )
                )
                output.write(message)
                output.flush()
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun readMessage(): ByteArray? {
        val header = ByteArray(4)
        var off = 0
        while (off < header.size) {
            val n = input.read(header, off, header.size - off)
            if (n < 0) return null
            off += n
        }
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length < 0 || length > MAX_MESSAGE_BYTES) return null
        val payload = ByteArray(length)
        off = 0
        while (off < length) {
            val n = input.read(payload, off, length - off)
            if (n < 0) return null
            off += n
        }
        return payload
    }
}
