package com.newax.aegis.sync

/**
 * Wire encoding for the SessionCrypto handshake messages (S2) — the frames
 * exchanged before the session exists. Deterministic text lines matching the
 * WireCodec house style, hex payloads, malformed → null:
 *   I|<deviceId>|<hex ephemeralPublicKey>|<hex signature>
 *   R|<deviceId>|<hex ephemeralPublicKey>|<hex signature>
 *   F|<hex Finished ciphertext>
 * deviceId is fingerprint-derived hex (`dev-…`) and never contains the field
 * separator, so no escaping is needed. These frames are intentionally NOT
 * sealed: they carry only public keys and signatures (design §8).
 */
object HandshakeWire {

    private const val SEP = '|'

    fun encodeHelloI(hello: SessionCrypto.HelloI): ByteArray =
        ("I$SEP${hello.deviceId}$SEP${Hex.encode(hello.ephemeralPublicKey)}$SEP${Hex.encode(hello.signature)}")
            .encodeToByteArray()

    fun encodeHelloR(hello: SessionCrypto.HelloR): ByteArray =
        ("R$SEP${hello.deviceId}$SEP${Hex.encode(hello.ephemeralPublicKey)}$SEP${Hex.encode(hello.signature)}")
            .encodeToByteArray()

    fun encodeFinished(finished: SessionCrypto.Finished): ByteArray =
        ("F$SEP${Hex.encode(finished.ciphertext)}").encodeToByteArray()

    fun decodeHelloI(bytes: ByteArray): SessionCrypto.HelloI? {
        val parts = splitFields(bytes, 4) ?: return null
        if (parts[0] != "I") return null
        return SessionCrypto.HelloI(
            deviceId = parts[1],
            ephemeralPublicKey = Hex.decode(parts[2]) ?: return null,
            signature = Hex.decode(parts[3]) ?: return null
        )
    }

    fun decodeHelloR(bytes: ByteArray): SessionCrypto.HelloR? {
        val parts = splitFields(bytes, 4) ?: return null
        if (parts[0] != "R") return null
        return SessionCrypto.HelloR(
            deviceId = parts[1],
            ephemeralPublicKey = Hex.decode(parts[2]) ?: return null,
            signature = Hex.decode(parts[3]) ?: return null
        )
    }

    fun decodeFinished(bytes: ByteArray): SessionCrypto.Finished? {
        val parts = splitFields(bytes, 2) ?: return null
        if (parts[0] != "F") return null
        return SessionCrypto.Finished(ciphertext = Hex.decode(parts[1]) ?: return null)
    }

    private fun splitFields(bytes: ByteArray, max: Int): List<String>? {
        val text = bytes.decodeToString()
        if (text.isEmpty()) return null
        val parts = text.split(SEP)
        if (parts.size != max) return null
        return parts
    }
}
