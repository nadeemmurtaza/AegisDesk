package com.newax.aegis.sync

/**
 * Encrypted proximity transfer — the "Quick Share, but end-to-end encrypted"
 * system (docs/SYNC_DESIGN.md §10.1). Runs over any reliable, ordered
 * [TransferChannel]: a TCP socket on a WiFi-Direct group, a Bluetooth channel,
 * or the LAN path. Every message is a [BlobCrypto] sealed blob, so the
 * channel (and anything sniffing it) sees only ciphertext and public
 * ephemeral keys — only the intended recipient's long-term key can unwrap.
 *
 * Flow (sender → receiver):
 *   INIT     sealed meta {senderDeviceId, senderEcdhPublicKey, fileName,
 *            sizeBytes, chunkCount, sha256Hex} to the receiver's long-term key
 *   ← ACCEPT sealed "accept" | "reject:<reason>" back to the sender's key
 *   CHUNK i  sealed 4-byte BE index + data (sequential, verified)
 *   DONE     sealed whole-file SHA-256
 *   ← COMPLETE sealed "complete"
 *
 * Failure modes are explicit [ProximityResult.Failed] values — wrong key,
 * tamper (AEAD), out-of-order chunks, hash mismatch, declined, timeout — never
 * exceptions. v1 is whole-content in memory (files up to 1 GiB); a streaming
 * variant for very large files is a named future slice.
 */
object ProximityTransfer {

    const val PROTO = "aegis-proximity-v1"
    const val CHUNK_SIZE = 64 * 1024

    private const val ACCEPT_TIMEOUT_MS = 30_000L
    private const val CHUNK_TIMEOUT_MS = 60_000L
    private const val COMPLETE_TIMEOUT_MS = 60_000L
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024 * 1024
    private const val MAX_CHUNKS = 1_000_000
    private const val ACCEPT_OK = "accept"
    private const val COMPLETE_OK = "complete"

    /** The encrypted-transfer conversation header, visible only to the recipient. */
    data class Meta(
        val senderDeviceId: String,
        /** The sender's long-term ECDH public key — where the ACCEPT reply goes. */
        val senderEcdhPublicKey: ByteArray,
        val fileName: String,
        val sizeBytes: Long,
        val chunkCount: Int,
        val sha256Hex: String
    )

    sealed interface Result {
        data class Sent(val sha256Hex: String, val chunks: Int) : Result
        data class Received(val fileName: String, val content: ByteArray, val sha256Hex: String) : Result
        data class Failed(val stage: String, val reason: String) : Result
    }

    /** Progress + completion callbacks; all methods are no-ops by default. */
    interface Progress {
        fun onChunk(index: Int, of: Int) = Unit
        fun onComplete() = Unit
        fun onFailed(stage: String, reason: String) = Unit
    }

    private object NoProgress : Progress

    /**
     * Sender side. [myKeyPair] is this device's long-term ECDH keypair (the
     * receiver learns [myKeyPair.publicKey] from the meta and replies to it);
     * [targetEcdhPublicKey] is the recipient's long-term ECDH public key.
     */
    fun send(
        crypto: Crypto,
        channel: TransferChannel,
        myKeyPair: KeyPair,
        targetEcdhPublicKey: ByteArray,
        senderDeviceId: String,
        fileName: String,
        content: ByteArray,
        progress: Progress = NoProgress
    ): Result {
        val chunks = chunkCount(content.size)
        val meta = Meta(
            senderDeviceId = senderDeviceId,
            senderEcdhPublicKey = myKeyPair.publicKey,
            fileName = sanitize(fileName),
            sizeBytes = content.size.toLong(),
            chunkCount = chunks,
            sha256Hex = Hex.encode(Sha256.digest(content))
        )
        val target = targetEcdhPublicKey
        if (!channel.write(encodeBlob(BlobCrypto.seal(crypto, encodeMeta(meta), target)))) {
            return fail(progress, "init", "write failed")
        }

        val acceptText = reply(crypto, channel, ACCEPT_TIMEOUT_MS, myKeyPair.privateKey)
            ?: return fail(progress, "accept", "no response (peer offline or wrong recipient)")
        if (acceptText.startsWith("reject:")) return fail(progress, "accept", acceptText.removePrefix("reject:"))
        if (acceptText != ACCEPT_OK) return fail(progress, "accept", "declined")

        for (i in 0 until chunks) {
            val start = i * CHUNK_SIZE
            val data = content.copyOfRange(start, minOf(start + CHUNK_SIZE, content.size))
            val payload = ByteArray(4 + data.size)
            writeInt(payload, i)
            data.copyInto(payload, 4)
            if (!channel.write(encodeBlob(BlobCrypto.seal(crypto, payload, target)))) {
                return fail(progress, "chunk", "write failed at chunk $i")
            }
            progress.onChunk(i, chunks)
        }

        if (!channel.write(encodeBlob(BlobCrypto.seal(crypto, meta.sha256Hex.encodeToByteArray(), target)))) {
            return fail(progress, "done", "write failed")
        }
        val completeText = reply(crypto, channel, COMPLETE_TIMEOUT_MS, myKeyPair.privateKey)
        if (completeText != COMPLETE_OK) return fail(progress, "complete", "receiver did not confirm: $completeText")
        progress.onComplete()
        return Result.Sent(meta.sha256Hex, chunks)
    }

    /**
     * Receiver side. [myKeyPair] is THIS device's long-term ECDH keypair;
     * [accept] is the user-confirmation gate (Quick Share's accept/reject
     * prompt) — returning false replies "reject:declined" and aborts before
     * any chunk is sent.
     */
    fun receive(
        crypto: Crypto,
        channel: TransferChannel,
        myKeyPair: KeyPair,
        myDeviceId: String,
        accept: (Meta) -> Boolean,
        progress: Progress = NoProgress
    ): Result {
        val initMsg = channel.read(CHUNK_TIMEOUT_MS)
            ?: return fail(progress, "init", "timeout waiting for transfer")
        val initBlob = decodeBlob(initMsg.decodeToString()) ?: return fail(progress, "init", "malformed envelope")
        val metaBytes = BlobCrypto.open(crypto, initBlob, myKeyPair.privateKey)
            ?: return fail(progress, "init", "cannot decrypt (wrong recipient or tampered)")
        val meta = decodeMeta(metaBytes) ?: return fail(progress, "init", "malformed meta")
        if (meta.sizeBytes < 0 || meta.sizeBytes > MAX_FILE_BYTES || meta.chunkCount < 1 || meta.chunkCount > MAX_CHUNKS) {
            return fail(progress, "init", "implausible meta (size=${meta.sizeBytes}, chunks=${meta.chunkCount})")
        }

        val senderKey = meta.senderEcdhPublicKey
        val accepted = try {
            accept(meta)
        } catch (e: Exception) {
            false
        }
        if (!accepted) {
            channel.write(encodeBlob(BlobCrypto.seal(crypto, "reject:declined".encodeToByteArray(), senderKey)))
            return fail(progress, "init", "declined by user")
        }
        if (!channel.write(encodeBlob(BlobCrypto.seal(crypto, ACCEPT_OK.encodeToByteArray(), senderKey)))) {
            return fail(progress, "accept", "write failed")
        }

        val buffer = ByteArray(meta.sizeBytes.toInt())
        for (i in 0 until meta.chunkCount) {
            val chunkMsg = channel.read(CHUNK_TIMEOUT_MS)
                ?: return fail(progress, "chunk", "timeout at chunk $i")
            val chunkBlob = decodeBlob(chunkMsg.decodeToString()) ?: return fail(progress, "chunk", "malformed at chunk $i")
            val payload = BlobCrypto.open(crypto, chunkBlob, myKeyPair.privateKey)
                ?: return fail(progress, "chunk", "tampered or wrong key at chunk $i")
            if (payload.size < 4) return fail(progress, "chunk", "short chunk at $i")
            val index = readInt(payload)
            if (index != i) return fail(progress, "chunk", "out of order: expected $i, got $index")
            val dataSize = payload.size - 4
            if (i * CHUNK_SIZE + dataSize > buffer.size) return fail(progress, "chunk", "overrun at chunk $i")
            payload.copyInto(buffer, i * CHUNK_SIZE, 4, payload.size)
            progress.onChunk(i, meta.chunkCount)
        }

        val doneMsg = channel.read(CHUNK_TIMEOUT_MS) ?: return fail(progress, "done", "timeout waiting for DONE")
        val doneBlob = decodeBlob(doneMsg.decodeToString()) ?: return fail(progress, "done", "malformed")
        val doneHash = BlobCrypto.open(crypto, doneBlob, myKeyPair.privateKey)
            ?: return fail(progress, "done", "tampered")
        val actualHash = Hex.encode(Sha256.digest(buffer))
        if (doneHash.decodeToString() != actualHash) return fail(progress, "done", "hash mismatch")
        if (!channel.write(encodeBlob(BlobCrypto.seal(crypto, COMPLETE_OK.encodeToByteArray(), senderKey)))) {
            return fail(progress, "complete", "write failed")
        }
        progress.onComplete()
        return Result.Received(meta.fileName, buffer, actualHash)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun fail(progress: Progress, stage: String, reason: String): Result.Failed {
        progress.onFailed(stage, reason)
        return Result.Failed(stage, reason)
    }

    private fun reply(crypto: Crypto, channel: TransferChannel, timeoutMs: Long, myPrivateKey: ByteArray): String? {
        val msg = channel.read(timeoutMs) ?: return null
        val blob = decodeBlob(msg.decodeToString()) ?: return null
        return BlobCrypto.open(crypto, blob, myPrivateKey)?.decodeToString()
    }

    private fun chunkCount(size: Int): Int = maxOf(1, (size + CHUNK_SIZE - 1) / CHUNK_SIZE)

    private fun sanitize(fileName: String): String = fileName.replace('\u001e', '_')

    private fun writeInt(out: ByteArray, value: Int) {
        out[0] = (value ushr 24).toByte()
        out[1] = (value ushr 16).toByte()
        out[2] = (value ushr 8).toByte()
        out[3] = value.toByte()
    }

    private fun readInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)

    // ── meta serialization (length-delimited fields; no JSON dep) ─────────────

    private const val FIELD_SEP = '\u001e'

    private fun encodeMeta(meta: Meta): ByteArray = listOf(
        meta.senderDeviceId,
        meta.fileName,
        meta.sizeBytes.toString(),
        meta.chunkCount.toString(),
        meta.sha256Hex,
        Hex.encode(meta.senderEcdhPublicKey)
    ).joinToString(FIELD_SEP.toString()).encodeToByteArray()

    private fun decodeMeta(bytes: ByteArray): Meta? {
        val parts = bytes.decodeToString().split(FIELD_SEP)
        if (parts.size != 6) return null
        val size = parts[2].toLongOrNull() ?: return null
        val chunks = parts[3].toIntOrNull() ?: return null
        val senderKey = Hex.decode(parts[5]) ?: return null
        return Meta(
            senderDeviceId = parts[0],
            fileName = parts[1],
            sizeBytes = size,
            chunkCount = chunks,
            sha256Hex = parts[4],
            senderEcdhPublicKey = senderKey
        )
    }

    // ── sealed-blob serialization (hex fields; the channel is message-based) ──

    private fun encodeBlob(blob: BlobCrypto.SealedBlob): ByteArray = listOf(
        Hex.encode(blob.ephemeralPublicKey),
        Hex.encode(blob.wrappedKeyNonce),
        Hex.encode(blob.wrappedKey),
        Hex.encode(blob.contentNonce),
        Hex.encode(blob.ciphertext)
    ).joinToString("|").encodeToByteArray()

    private fun decodeBlob(text: String): BlobCrypto.SealedBlob? {
        val parts = text.split('|')
        if (parts.size != 5) return null
        val ephemeral = Hex.decode(parts[0]) ?: return null
        val wrappedKeyNonce = Hex.decode(parts[1]) ?: return null
        val wrappedKey = Hex.decode(parts[2]) ?: return null
        val contentNonce = Hex.decode(parts[3]) ?: return null
        val ciphertext = Hex.decode(parts[4]) ?: return null
        return BlobCrypto.SealedBlob(ephemeral, wrappedKeyNonce, wrappedKey, contentNonce, ciphertext)
    }
}
