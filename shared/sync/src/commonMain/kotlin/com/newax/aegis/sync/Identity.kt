package com.newax.aegis.sync

/**
 * A device's public identity (docs/SYNC_DESIGN.md §3): one Ed25519 signing
 * keypair + one X25519 ECDH keypair, both platform-encoded. Private keys never
 * live in this record — they stay in the KeyStore.
 *
 * [deviceId] is derived from the fingerprint, so it is stable across restarts
 * and unforgeable (you can't claim a deviceId you don't hold the key for).
 */
data class DeviceIdentity(
    val displayName: String,
    val signPublicKey: ByteArray,
    val ecdhPublicKey: ByteArray
) {
    val fingerprintHex: String
        get() = Hex.encode(Sha256.digest(signPublicKey + ecdhPublicKey))

    /** Short human display — "abcd1234". */
    val shortFingerprint: String
        get() = fingerprintHex.take(8)

    /** Stable id used in journal entries / version vectors — "dev-abcd1234ef". */
    val deviceId: String
        get() = "dev-" + fingerprintHex.take(10)

    override fun equals(other: Any?): Boolean =
        other is DeviceIdentity &&
            other.displayName == displayName &&
            other.signPublicKey.contentEquals(signPublicKey) &&
            other.ecdhPublicKey.contentEquals(ecdhPublicKey)

    override fun hashCode(): Int = fingerprintHex.hashCode()
}

/** The identity as stored in the keystore — public record + both private keys. */
data class StoredIdentity(
    val identity: DeviceIdentity,
    val signPrivateKey: ByteArray,
    val ecdhPrivateKey: ByteArray
)

/** Identity generation — called once at first launch. */
object Identity {

    fun generate(crypto: Crypto, displayName: String): StoredIdentity {
        val sign = crypto.newSignKeyPair()
        val ecdh = crypto.newEcdhKeyPair()
        return StoredIdentity(
            identity = DeviceIdentity(displayName, sign.publicKey, ecdh.publicKey),
            signPrivateKey = sign.privateKey,
            ecdhPrivateKey = ecdh.privateKey
        )
    }
}
