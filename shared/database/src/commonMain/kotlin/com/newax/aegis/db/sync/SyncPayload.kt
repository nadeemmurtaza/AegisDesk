package com.newax.aegis.db.sync

/**
 * Deterministic, dependency-free wire codec for sync journal RECORD payloads
 * (docs/SYNC_DESIGN.md §4 — the "journal is the source of truth" model).
 *
 * A RECORD payload is the full state of one record (LWW per key): an ordered
 * list of string fields. shared/database commonMain has no JSON dependency
 * (org.json exists only in the app targets) and no java.* (KMP common code),
 * so this is a length-prefixed binary format — no escaping rules to get
 * wrong, no parser to vendor, pure Kotlin.
 *
 * Layout (all integers big-endian):
 *   int32  fieldCount
 *   per field: int32 keyLen, key UTF-8, int32 valLen, val UTF-8
 *
 * [encode] and [decode] are inverses; decode returns an empty map on a
 * truncated/corrupt payload (the caller's per-entry try/catch handles the
 * rest).
 */
object SyncPayload {

    fun encode(fields: List<Pair<String, String>>): ByteArray {
        // No java.io in commonMain: build the output from a segment list.
        val segments = ArrayList<ByteArray>()
        var size = 0
        fun push(bytes: ByteArray) {
            segments.add(bytes)
            size += bytes.size
        }
        push(intBytes(fields.size))
        for ((k, v) in fields) {
            val kb = k.encodeToByteArray()
            val vb = v.encodeToByteArray()
            push(intBytes(kb.size))
            push(kb)
            push(intBytes(vb.size))
            push(vb)
        }
        val out = ByteArray(size)
        var pos = 0
        for (segment in segments) {
            segment.copyInto(out, pos)
            pos += segment.size
        }
        return out
    }

    fun decode(bytes: ByteArray): Map<String, String> {
        if (bytes.size < 4) return emptyMap()
        val count = readInt(bytes, 0)
        if (count < 0 || count > 256) return emptyMap()
        var pos = 4
        val out = LinkedHashMap<String, String>(count)
        for (i in 0 until count) {
            val k = readField(bytes, pos) ?: return emptyMap()
            val kLen = fieldByteLength(bytes, pos)
            if (kLen < 0) return emptyMap()
            pos += kLen
            val v = readField(bytes, pos) ?: return emptyMap()
            val vLen = fieldByteLength(bytes, pos)
            if (vLen < 0) return emptyMap()
            pos += vLen
            out[k] = v
        }
        return out
    }

    /** Consumed bytes (4 + UTF-8 length) of the field starting at [pos], or -1 if malformed. */
    private fun fieldByteLength(bytes: ByteArray, pos: Int): Int {
        if (pos + 4 > bytes.size) return -1
        val len = readInt(bytes, pos)
        if (len < 0 || pos + 4 + len > bytes.size) return -1
        return 4 + len
    }

    private fun readField(bytes: ByteArray, pos: Int): String? {
        val len = fieldByteLength(bytes, pos)
        if (len < 0) return null
        return bytes.decodeToString(pos + 4, pos + len)
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    private fun readInt(bytes: ByteArray, pos: Int): Int =
        ((bytes[pos].toInt() and 0xFF) shl 24) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
            (bytes[pos + 3].toInt() and 0xFF)
}
