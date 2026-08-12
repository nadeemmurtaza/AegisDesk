package com.newax.aegis.sync

/**
 * The on-disk line format for the sync identity and paired peers
 * (| separated, hex-encoded keys; display names escaped). Shared by the JVM
 * dev store ([FileKeyStore]) and the Android production store
 * ([AndroidSyncKeyStore]) so a store can be swapped without losing records.
 */
internal object IdentityCodec {

    fun encodeIdentity(identity: StoredIdentity): String = listOf(
        escape(identity.identity.displayName),
        Hex.encode(identity.signPrivateKey),
        Hex.encode(identity.identity.signPublicKey),
        Hex.encode(identity.ecdhPrivateKey),
        Hex.encode(identity.identity.ecdhPublicKey),
        identity.identity.deviceId,
        identity.identity.fingerprintHex
    ).joinToString("|")

    fun decodeIdentity(line: String): StoredIdentity? {
        val f = splitEscaped(line) ?: return null
        if (f.size != 7) return null
        val name = unescape(f[0]) ?: return null
        val signPriv = Hex.decode(f[1]) ?: return null
        val signPub = Hex.decode(f[2]) ?: return null
        val ecdhPriv = Hex.decode(f[3]) ?: return null
        val ecdhPub = Hex.decode(f[4]) ?: return null
        return StoredIdentity(DeviceIdentity(name, signPub, ecdhPub), signPriv, ecdhPriv)
    }

    fun encodePeer(peer: PairedPeer): String = listOf(
        escape(peer.displayName),
        peer.deviceId,
        Hex.encode(peer.signPublicKey),
        Hex.encode(peer.ecdhPublicKey),
        peer.pairedAtMs.toString()
    ).joinToString("|")

    fun decodePeer(line: String): PairedPeer? {
        val f = splitEscaped(line) ?: return null
        if (f.size != 5) return null
        val name = unescape(f[0]) ?: return null
        val signPub = Hex.decode(f[2]) ?: return null
        val ecdhPub = Hex.decode(f[3]) ?: return null
        val pairedAt = f[4].toLongOrNull() ?: return null
        return PairedPeer(f[1], name, signPub, ecdhPub, pairedAt)
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("|", "\\|")

    /** Escape-aware field split — a lone trailing '\\' is malformed (R9: null, never throw). */
    private fun splitEscaped(s: String): List<String>? {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                if (i + 1 >= s.length) return null // dangling escape
                cur.append(c).append(s[i + 1])
                i += 2
            } else if (c == '|') {
                out.add(cur.toString())
                cur.setLength(0)
                i++
            } else {
                cur.append(c)
                i++
            }
        }
        out.add(cur.toString())
        return out
    }

    private fun unescape(s: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                if (i + 1 >= s.length) return null
                out.append(s[i + 1])
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }
}
