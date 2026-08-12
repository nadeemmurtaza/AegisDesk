package com.newax.aegis.sync

/**
 * Deterministic, dependency-free wire codec for the sync protocol messages
 * (docs/SYNC_DESIGN.md §9). The transport slice may swap this for CBOR; the
 * message model below is the contract.
 *
 * Format — one message per line, '|'-separated fields, '\'-escaped strings,
 * payloads hex-encoded:
 *   V|<deviceId>|<peer>:<wall>:<counter>,<peer>:<wall>:<counter>,…   (VECTOR_EXCH)
 *   D|<deviceId>|<wall>:<counter>|<entry>|<entry>…                    (DELTA)
 *   A|<deviceId>|<wall>:<counter>                                     (ACK_VECTOR)
 * where <entry> = <opId>|<deviceId>|<wall>|<counter>|<LOG|RECORD>|<table>|<key>|<hex>|<0|1>|<createdAt>
 *
 * Every decode returns null on malformed input (R9 — named failure mode,
 * never a crash); the recipient drops the line and audits it. Contract: string
 * identifiers (device/peer ids, op ids, table names, keys) must not contain
 * ':' — satisfied by the design (hex Ed25519 fingerprints, the 17 table-name
 * constants, numeric record keys). '|', '\', '\n', and the record separator
 * are escaped and safe anywhere.
 */
object WireCodec {

    sealed interface SyncMessage {
        /** My journal watermark row for every peer I know — what the peer sends me. */
        data class VectorExchange(val deviceId: String, val watermarks: Map<String, Hlc>) : SyncMessage

        /** Journal entries the sender has that the receiver is missing. */
        data class Delta(val deviceId: String, val fromHlc: Hlc, val entries: List<SyncEntry>) : SyncMessage

        /** Receiver's watermark after applying a DELTA — advances GC. */
        data class AckVector(val deviceId: String, val ackedHlc: Hlc) : SyncMessage
    }

    private const val SEP = '|'
    private const val ENTRY_SEP = '\u001e' // record separator between entries
    private const val PEER_SEP = ','
    private const val HLC_SEP = ':'
    private const val ESC = '\\'

    // ── encode ────────────────────────────────────────────────────────────────

    fun encode(message: SyncMessage): String = when (message) {
        is SyncMessage.VectorExchange -> "V" + SEP + escape(message.deviceId) + SEP +
            message.watermarks.entries.joinToString(PEER_SEP.toString()) { (peer, hlc) ->
                escape(peer) + HLC_SEP + hlc.wall + HLC_SEP + hlc.counter
            }

        is SyncMessage.Delta -> buildString {
            append("D").append(SEP).append(escape(message.deviceId))
            append(SEP).append(message.fromHlc.wall).append(HLC_SEP).append(message.fromHlc.counter)
            message.entries.forEach { append(ENTRY_SEP).append(encodeEntry(it)) }
        }

        is SyncMessage.AckVector -> "A" + SEP + escape(message.deviceId) + SEP +
            message.ackedHlc.wall + HLC_SEP + message.ackedHlc.counter
    }

    private fun encodeEntry(e: SyncEntry): String =
        escape(e.opId) + SEP + escape(e.deviceId) + SEP +
            e.hlc.wall + SEP + e.hlc.counter + SEP +
            e.kind.name + SEP + escape(e.table) + SEP + escape(e.key) + SEP +
            Hex.encode(e.payload) + SEP + (if (e.tombstone) "1" else "0") + SEP + e.createdAt

    // ── decode ────────────────────────────────────────────────────────────────

    fun decode(line: String): SyncMessage? {
        if (line.isEmpty()) return null
        return when (line[0]) {
            'V' -> decodeVector(line)
            'D' -> decodeDelta(line)
            'A' -> decodeAck(line)
            else -> null
        }
    }

    private fun decodeVector(line: String): SyncMessage? {
        val parts = split(line, 3) ?: return null
        if (parts[0] != "V") return null
        val deviceId = unescape(parts[1]) ?: return null
        val watermarks = mutableMapOf<String, Hlc>()
        if (parts[2].isNotEmpty()) {
            for (pair in parts[2].split(PEER_SEP)) {
                val h = pair.split(HLC_SEP)
                if (h.size != 3) return null
                val peer = unescape(h[0]) ?: return null
                val wall = h[1].toLongOrNull() ?: return null
                val counter = h[2].toLongOrNull() ?: return null
                if (wall < 0 || counter < 0) return null
                watermarks[peer] = Hlc(wall, counter)
            }
        }
        return SyncMessage.VectorExchange(deviceId, watermarks)
    }

    private fun decodeDelta(line: String): SyncMessage? {
        val first = line.indexOf(SEP)
        if (first < 0) return null
        val second = line.indexOf(SEP, first + 1)
        if (second < 0) return null
        if (line.substring(0, first) != "D") return null
        val deviceId = unescape(line.substring(first + 1, second)) ?: return null
        val hlcIdx = line.indexOf(HLC_SEP, second + 1)
        if (hlcIdx < 0) return null
        val wall = line.substring(second + 1, hlcIdx).toLongOrNull() ?: return null
        val counterEnd = line.indexOf(ENTRY_SEP, hlcIdx + 1).let { if (it < 0) line.length else it }
        val counter = line.substring(hlcIdx + 1, counterEnd).toLongOrNull() ?: return null
        val entries = mutableListOf<SyncEntry>()
        if (counterEnd < line.length) {
            for (raw in line.substring(counterEnd).split(ENTRY_SEP)) {
                if (raw.isEmpty()) continue
                val e = decodeEntry(raw) ?: return null
                entries.add(e)
            }
        }
        return SyncMessage.Delta(deviceId, Hlc(wall, counter), entries)
    }

    private fun decodeAck(line: String): SyncMessage? {
        val parts = split(line, 3) ?: return null
        if (parts[0] != "A") return null
        val deviceId = unescape(parts[1]) ?: return null
        val h = parts[2].split(HLC_SEP)
        if (h.size != 2) return null
        val wall = h[0].toLongOrNull() ?: return null
        val counter = h[1].toLongOrNull() ?: return null
        if (wall < 0 || counter < 0) return null
        return SyncMessage.AckVector(deviceId, Hlc(wall, counter))
    }

    private fun decodeEntry(raw: String): SyncEntry? {
        val parts = split(raw, 10) ?: return null
        val opId = unescape(parts[0]) ?: return null
        val deviceId = unescape(parts[1]) ?: return null
        val wall = parts[2].toLongOrNull() ?: return null
        val counter = parts[3].toLongOrNull() ?: return null
        val kind = when (parts[4]) {
            SyncEntry.Kind.LOG.name -> SyncEntry.Kind.LOG
            SyncEntry.Kind.RECORD.name -> SyncEntry.Kind.RECORD
            else -> return null
        }
        val table = unescape(parts[5]) ?: return null
        val key = unescape(parts[6]) ?: return null
        val payload = Hex.decode(parts[7]) ?: return null
        val tombstone = when (parts[8]) {
            "0" -> false
            "1" -> true
            else -> return null
        }
        val createdAt = parts[9].toLongOrNull() ?: return null
        return SyncEntry(opId, deviceId, Hlc(wall, counter), kind, table, key, payload, tombstone, createdAt)
    }

    // ── field splitting with escaping (a raw SEP never splits inside a field) ──

    /**
     * Split into exactly [max] fields. Escapes are preserved VERBATIM (backslash
     * + following char) so field boundaries are still found, and [unescape]
     * resolves them exactly once afterwards — never unescape split output twice.
     */
    private fun split(line: String, max: Int): List<String>? {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == ESC) {
                if (i + 1 >= line.length) return null // dangling escape — malformed
                current.append(ch).append(line[i + 1])
                i += 2
            } else if (ch == SEP) {
                if (fields.size == max - 1) return null // an (max+1)-th field appeared
                fields.add(current.toString())
                current.setLength(0)
                i++
            } else {
                current.append(ch)
                i++
            }
        }
        fields.add(current.toString())
        if (fields.size != max) return null
        return fields
    }

    // ── escaping (mirrors the splitter: backslash, separator, newline, entry sep) ──

    private fun escape(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            ESC -> { append(ESC); append(ESC) }
            SEP -> { append(ESC); append(SEP) }
            '\n' -> { append(ESC); append('n') }
            ENTRY_SEP -> { append(ESC); append('e') }
            else -> append(ch)
        }
    }

    private fun unescape(s: String): String? {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == ESC) {
                if (i + 1 >= s.length) return null
                val next = s[i + 1]
                when (next) {
                    ESC -> out.append(ESC)
                    SEP -> out.append(SEP)
                    'n' -> out.append('\n')
                    'e' -> out.append(ENTRY_SEP)
                    else -> return null
                }
                i += 2
            } else {
                out.append(ch)
                i += 1
            }
        }
        return out.toString()
    }
}

/** Minimal hex codec for opaque payloads (commonMain has no base64). */
object Hex {

    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(DIGITS[v ushr 4])
            append(DIGITS[v and 0x0F])
        }
    }

    /** Null on odd length or non-hex characters — never throws. */
    fun decode(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            val hi = digit(s[i]) ?: return null
            val lo = digit(s[i + 1]) ?: return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun digit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}
