package com.newax.aegis.sync

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The encrypted Quick Share protocol, exercised end-to-end: sender and
 * receiver run concurrently over cross-wired in-memory channels with the
 * deterministic FakeCrypto (real SHA-256/HMAC/HKDF). Covers the happy path,
 * empty files, wrong-recipient rejection, AEAD tamper, user decline, and
 * progress reporting.
 */
class ProximityTransferTest {

    private val crypto = FakeCrypto(seed = 7)

    private fun content(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun runSender(
        channel: TransferChannel,
        senderKeys: KeyPair,
        receiverPub: ByteArray,
        fileName: String,
        data: ByteArray,
        progress: ProximityTransfer.Progress = NoopProgress
    ): Thread = Thread {
        ProximityTransfer.send(crypto, channel, senderKeys, receiverPub, "dev-sender", fileName, data, progress)
    }.apply {
        isDaemon = true
        start()
    }

    @Test
    fun fullFileRoundTrip() {
        val (sender, receiver) = InMemoryTransferChannel.pipe()
        val senderKeys = crypto.newEcdhKeyPair()
        val receiverKeys = crypto.newEcdhKeyPair()
        val data = content(150_000) // 3 chunks of 64 KiB

        val senderThread = runSender(sender, senderKeys, receiverKeys.publicKey, "photo.jpg", data)
        val result = ProximityTransfer.receive(crypto, receiver, receiverKeys, "dev-receiver") { true }

        val received = assertIs<ProximityTransfer.Result.Received>(result)
        assertEquals("photo.jpg", received.fileName)
        assertContentEquals(data, received.content, "received bytes must match exactly")
        assertEquals(Hex.encode(crypto.sha256(data)), received.sha256Hex)
        senderThread.join(5_000)
        assertTrue(!senderThread.isAlive, "sender should have completed")
    }

    @Test
    fun emptyFileRoundTrip() {
        val (sender, receiver) = InMemoryTransferChannel.pipe()
        val senderKeys = crypto.newEcdhKeyPair()
        val receiverKeys = crypto.newEcdhKeyPair()

        val senderThread = runSender(sender, senderKeys, receiverKeys.publicKey, "note.txt", ByteArray(0))
        val result = ProximityTransfer.receive(crypto, receiver, receiverKeys, "dev-receiver") { true }

        val received = assertIs<ProximityTransfer.Result.Received>(result)
        assertEquals(0, received.content.size)
        senderThread.join(5_000)
    }

    @Test
    fun wrongRecipientCannotDecrypt() {
        val (sender, receiver) = InMemoryTransferChannel.pipe()
        val senderKeys = crypto.newEcdhKeyPair()
        val realReceiver = crypto.newEcdhKeyPair()
        val eavesdropper = crypto.newEcdhKeyPair()

        // Sealed to the real receiver; the eavesdropper tries to read it.
        val senderThread = runSender(sender, senderKeys, realReceiver.publicKey, "secret.pdf", content(10_000))
        val result = ProximityTransfer.receive(crypto, receiver, eavesdropper, "dev-eavesdropper") { true }

        val failed = assertIs<ProximityTransfer.Result.Failed>(result)
        assertEquals("init", failed.stage)
        senderThread.join(5_000)
    }

    @Test
    fun tamperedChunkFails() {
        val raw = InMemoryTransferChannel.pipe()
        // Corrupt the sender's 2nd write — the first CHUNK (message index 1).
        val sender = TamperingChannel(raw.first, corruptMessage = 1)
        val senderKeys = crypto.newEcdhKeyPair()
        val receiverKeys = crypto.newEcdhKeyPair()

        val senderThread = runSender(sender, senderKeys, receiverKeys.publicKey, "doc.pdf", content(70_000))
        val result = ProximityTransfer.receive(crypto, raw.second, receiverKeys, "dev-receiver") { true }

        val failed = assertIs<ProximityTransfer.Result.Failed>(result)
        assertEquals("chunk", failed.stage)
        assertTrue("tampered" in failed.reason, "reason should name the tamper, got: ${failed.reason}")
        senderThread.join(5_000)
    }

    @Test
    fun declinedByUserSendsNothing() {
        val (sender, receiver) = InMemoryTransferChannel.pipe()
        val senderKeys = crypto.newEcdhKeyPair()
        val receiverKeys = crypto.newEcdhKeyPair()
        val data = content(20_000)
        val senderResult = java.util.concurrent.atomic.AtomicReference<ProximityTransfer.Result>()

        val senderThread = Thread {
            senderResult.set(
                ProximityTransfer.send(crypto, sender, senderKeys, receiverKeys.publicKey, "pic.png", data)
            )
        }.apply {
            isDaemon = true
            start()
        }
        val result = ProximityTransfer.receive(crypto, receiver, receiverKeys, "dev-receiver") { false }

        val failed = assertIs<ProximityTransfer.Result.Failed>(result)
        assertEquals("init", failed.stage)
        assertTrue("declined" in failed.reason)
        senderThread.join(5_000)

        // The sender must observe the same decline (its channel never wrote a chunk).
        val senderFailed = assertIs<ProximityTransfer.Result.Failed>(senderResult.get())
        assertEquals("accept", senderFailed.stage)
        assertTrue("declined" in senderFailed.reason)
    }

    @Test
    fun progressIsReported() {
        val (sender, receiver) = InMemoryTransferChannel.pipe()
        val senderKeys = crypto.newEcdhKeyPair()
        val receiverKeys = crypto.newEcdhKeyPair()
        val data = content(200_000) // 4 chunks
        val sentChunks = java.util.concurrent.atomic.AtomicInteger()

        val senderThread = Thread {
            ProximityTransfer.send(
                crypto, sender, senderKeys, receiverKeys.publicKey, "vid.mp4", data,
                object : ProximityTransfer.Progress {
                    override fun onChunk(index: Int, of: Int) {
                        sentChunks.incrementAndGet()
                    }
                }
            )
        }.apply { isDaemon = true; start() }
        val receivedChunks = java.util.concurrent.atomic.AtomicInteger()
        val result = ProximityTransfer.receive(
            crypto, receiver, receiverKeys, "dev-receiver",
            accept = { true },
            progress = object : ProximityTransfer.Progress {
                override fun onChunk(index: Int, of: Int) {
                    receivedChunks.incrementAndGet()
                }
            }
        )

        assertIs<ProximityTransfer.Result.Received>(result)
        senderThread.join(5_000)
        assertEquals(4, receivedChunks.get(), "receiver should see all 4 chunks")
        assertEquals(4, sentChunks.get(), "sender should report all 4 chunks")
    }

    private object NoopProgress : ProximityTransfer.Progress
}

/** Cross-wired in-memory transfer channels — one side's write is the other's read. */
internal class InMemoryTransferChannel : TransferChannel {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private var peer: InMemoryTransferChannel? = null

    fun link(other: InMemoryTransferChannel) {
        peer = other
    }

    override fun read(timeoutMs: Long): ByteArray? = try {
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        null
    }

    override fun write(message: ByteArray): Boolean {
        peer?.queue?.put(message)
        return true
    }

    override fun close() {
        queue.clear()
    }

    companion object {
        fun pipe(): Pair<InMemoryTransferChannel, InMemoryTransferChannel> {
            val a = InMemoryTransferChannel()
            val b = InMemoryTransferChannel()
            a.link(b)
            b.link(a)
            return a to b
        }
    }
}

/**
 * Interposing channel that corrupts one byte of the [corruptMessage]-th
 * message (0-indexed writes) — exercises the AEAD tamper path.
 */
internal class TamperingChannel(
    private val delegate: TransferChannel,
    private val corruptMessage: Int
) : TransferChannel {

    private var writes = 0

    override fun read(timeoutMs: Long): ByteArray? = delegate.read(timeoutMs)

    override fun write(message: ByteArray): Boolean {
        val index = writes++
        val out = if (index == corruptMessage) {
            message.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }
        } else {
            message
        }
        return delegate.write(out)
    }

    override fun close() = delegate.close()
}
