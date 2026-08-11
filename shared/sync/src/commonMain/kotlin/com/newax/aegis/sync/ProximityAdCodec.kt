package com.newax.aegis.sync

/**
 * The byte format for a proximity advertisement (docs/SYNC_DESIGN.md §10.1,
 * P2) — what a BLE advertiser puts in its manufacturer-data field and a
 * scanner decodes. Deterministic and platform-free so Android (BLE) and iOS
 * (CoreBluetooth) advertise and read the same bytes:
 *
 *   [version:1][deviceId:utf8][0x00][displayName:utf8]
 *
 * The BLE advertisement packet is 31 bytes total (2 header + 2 company id
 * leaves ~27 for this payload), so [displayName] is truncated to fit and the
 * advertisement carries identity only — the bulk transfer runs over the
 * WiFi-Direct socket, not BLE.
 */
object ProximityAdCodec {

    /** Manufacturer-specific-data company id (arbitrary, unused range). */
    const val COMPANY_ID = 0x0A45

    const val VERSION = 0x01

    /** Usable payload budget in a 31-byte connectable advertisement. */
    const val MAX_AD_BYTES = 27

    private const val FIELD_SEP = 0x00

    /**
     * Encode [deviceId] + [displayName] into an advertisement payload.
     * Null when even [deviceId] alone cannot fit [maxBytes] (caller reports
     * "cannot advertise", never crashes).
     */
    fun encode(deviceId: String, displayName: String, maxBytes: Int = MAX_AD_BYTES): ByteArray? {
        val id = deviceId.encodeToByteArray()
        if (id.isEmpty() || id.size + 2 > maxBytes) return null
        val name = displayName.encodeToByteArray()
        val nameBudget = maxBytes - 2 - id.size
        val clipped = if (name.size <= nameBudget) name else name.copyOfRange(0, nameBudget)
        val out = ByteArray(2 + id.size + clipped.size)
        out[0] = VERSION
        id.copyInto(out, 1)
        out[1 + id.size] = FIELD_SEP
        clipped.copyInto(out, 2 + id.size)
        return out
    }

    /**
     * Decode an advertisement payload back to (deviceId, displayName). Null
     * on any malformed input (bad version, no separator, blank id) — a
     * foreign advertisement is data, never trusted.
     */
    fun decode(bytes: ByteArray): Pair<String, String>? {
        if (bytes.size < 3 || bytes[0] != VERSION) return null
        val sep = bytes.indexOf(FIELD_SEP, 1)
        if (sep <= 1) return null
        val id = bytes.copyOfRange(1, sep).decodeToString()
        if (id.isBlank()) return null
        val name = bytes.copyOfRange(sep + 1, bytes.size).decodeToString()
        return id to name
    }
}
