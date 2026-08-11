package com.newax.aegis.sync

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The TCP [TransferChannel] over loopback sockets: full-duplex message
 * round-trips (including a 2 MiB message), read timeout, and closed-channel
 * EOF.
 */
class TcpTransferChannelTest {

    @Test
    fun loopbackRoundTrip() {
        val server = ServerSocket(0)
        val clientConnected = CountDownLatch(1)
        val serverThread = Thread {
            val tcp = TcpTransferChannel.accept(server, 10_000)
            checkNotNull(tcp)
            assertTrue(clientConnected.await(5, TimeUnit.SECONDS))
            val msg = tcp.read(5_000)
            assertEquals("hello", checkNotNull(msg).decodeToString())
            assertTrue(tcp.write("world".encodeToByteArray()))
            tcp.close()
        }.apply { start() }

        val client = checkNotNull(TcpTransferChannel.connect("127.0.0.1", server.localPort))
        clientConnected.countDown()
        assertTrue(client.write("hello".encodeToByteArray()))
        val reply = client.read(5_000)
        assertEquals("world", checkNotNull(reply).decodeToString())
        client.close()
        serverThread.join(5_000)
        server.close()
    }

    @Test
    fun largeMessageRoundTrip() {
        val server = ServerSocket(0)
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val serverThread = Thread {
            val tcp = TcpTransferChannel.accept(server, 10_000)
            checkNotNull(tcp)
            val msg = tcp.read(10_000)
            checkNotNull(msg)
            assertContentEquals(payload, msg)
            tcp.close()
        }.apply { start() }

        val client = checkNotNull(TcpTransferChannel.connect("127.0.0.1", server.localPort))
        assertTrue(client.write(payload))
        serverThread.join(10_000)
        client.close()
        server.close()
    }

    @Test
    fun readTimesOut() {
        val server = ServerSocket(0)
        val client = checkNotNull(TcpTransferChannel.connect("127.0.0.1", server.localPort))
        val start = System.currentTimeMillis()
        assertNull(client.read(300))
        assertTrue(System.currentTimeMillis() - start >= 280, "read must block until the timeout")
        client.close()
        server.close()
    }

    @Test
    fun closedChannelReadsNull() {
        val server = ServerSocket(0)
        val client = checkNotNull(TcpTransferChannel.connect("127.0.0.1", server.localPort))
        client.close()
        assertNull(client.read(500))
        server.close()
    }
}
