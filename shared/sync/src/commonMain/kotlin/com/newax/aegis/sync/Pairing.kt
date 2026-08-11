package com.newax.aegis.sync

/**
 * Device pairing (docs/SYNC_DESIGN.md §3): initiator shows a QR (identity
 * keys + nonce), the responder scans, both compute the same 6-digit SAS, the
 * user confirms on both screens, and each side stores the other as a
 * [PairedPeer]. The SAS is the only MITM defense point — it must be
 * human-verified on both devices.
 */
object Pairing {

    const val PROTOCOL_VERSION = 1
    private const val QR_PREFIX = "aegis-pair-v1"

    /** The QR payload (design §9 PAIR_INIT). Public keys are platform-encoded. */
    data class PairingRequest(
        val version: Int,
        val displayName: String,
        val signPublicKey: ByteArray,
        val ecdhPublicKey: ByteArray,
        val nonce: ByteArray
    ) {
        fun encode(): String = listOf(
            QR_PREFIX,
            version.toString(),
            escapeName(displayName),
            Hex.encode(signPublicKey),
            Hex.encode(ecdhPublicKey),
            Hex.encode(nonce)
        ).joinToString("|")

        companion object {
            /** Null on any malformed QR payload — never throws (R9). */
            fun decode(qr: String): PairingRequest? {
                val parts = splitEscaped(qr) ?: return null
                if (parts.size != 6) return null
                if (parts[0] != QR_PREFIX) return null
                val version = parts[1].toIntOrNull() ?: return null
                val signKey = Hex.decode(parts[3]) ?: return null
                val ecdhKey = Hex.decode(parts[4]) ?: return null
                val nonce = Hex.decode(parts[5]) ?: return null
                if (version != PROTOCOL_VERSION) return null
                if (nonce.isEmpty() || nonce.size > 64) return null
                return PairingRequest(version, parts[2], signKey, ecdhKey, nonce)
            }

            /** Escape-aware split: '\|' and '\\' stay inside their field. */
            private fun splitEscaped(s: String): List<String>? {
                val out = mutableListOf<String>()
                val cur = StringBuilder()
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    if (c == '\\') {
                        if (i + 1 >= s.length) return null // dangling escape
                        cur.append(s[i + 1])
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
        }

        private fun escapeName(name: String): String = name.replace("\\", "\\\\").replace("|", "\\|")
    }

    /** Create the initiator's QR payload (fresh nonce each time). */
    fun createRequest(crypto: Crypto, identity: DeviceIdentity): PairingRequest =
        PairingRequest(
            version = PROTOCOL_VERSION,
            displayName = identity.displayName,
            signPublicKey = identity.signPublicKey,
            ecdhPublicKey = identity.ecdhPublicKey,
            nonce = crypto.randomBytes(16)
        )

    /**
     * The 6-digit short authentication string — identical on both devices:
     * first 20 bits of SHA-256(initiatorSignKey || responderSignKey || nonce),
     * mod 1,000,000, zero-padded.
     */
    fun sas(
        initiatorSignKey: ByteArray,
        responderSignKey: ByteArray,
        nonce: ByteArray
    ): String {
        val digest = Sha256.digest(initiatorSignKey + responderSignKey + nonce)
        val bits = ((digest[0].toInt() and 0xFF) shl 12) or
            ((digest[1].toInt() and 0xFF) shl 4) or
            ((digest[2].toInt() and 0xFF) ushr 4)
        return (bits % 1_000_000).toString().padStart(6, '0')
    }

    /**
     * Called on the RESPONDER after the user confirmed the SAS matches:
     * returns the peer record for the initiator.
     */
    fun confirmResponder(
        request: PairingRequest,
        myDeviceId: String,
        mySignPublicKey: ByteArray,
        nowMs: Long
    ): PairedPeer = PairedPeer(
        deviceId = "dev-" + Hex.encode(Sha256.digest(request.signPublicKey + request.ecdhPublicKey)).take(10),
        displayName = request.displayName,
        signPublicKey = request.signPublicKey,
        ecdhPublicKey = request.ecdhPublicKey,
        pairedAtMs = nowMs
    ).also { require(it.deviceId != myDeviceId) { "cannot pair with self" } }

    /**
     * Called on the INITIATOR once it has the responder's keys (delivered in
     * the first session message) and the user confirmed the SAS.
     */
    fun confirmInitiator(
        responderSignPublicKey: ByteArray,
        responderEcdhPublicKey: ByteArray,
        responderDisplayName: String,
        responderDeviceId: String,
        myDeviceId: String,
        nowMs: Long
    ): PairedPeer {
        require(responderDeviceId != myDeviceId) { "cannot pair with self" }
        return PairedPeer(
            deviceId = responderDeviceId,
            displayName = responderDisplayName,
            signPublicKey = responderSignPublicKey,
            ecdhPublicKey = responderEcdhPublicKey,
            pairedAtMs = nowMs
        )
    }
}
