package com.newax.aegis.sync

/**
 * Where device identity and paired peers live (docs/SYNC_DESIGN.md §3).
 * Implementations are platform keystores in production (Android Keystore /
 * Keychain / DPAPI — `AndroidSyncKeyStore` on Android, `OsKeyStore` on the
 * JVM desktops); [InMemoryKeyStore] serves tests, and jvmAndroidMain ships
 * the dev [FileKeyStore].
 */
interface KeyStore {

    fun loadIdentity(): StoredIdentity?

    fun saveIdentity(identity: StoredIdentity)

    fun pairedPeers(): List<PairedPeer>

    fun savePeer(peer: PairedPeer)

    fun removePeer(deviceId: String)
}

/** A device this one has paired with (public keys only — no secrets). */
data class PairedPeer(
    val deviceId: String,
    val displayName: String,
    val signPublicKey: ByteArray,
    val ecdhPublicKey: ByteArray,
    val pairedAtMs: Long
) {
    val fingerprintHex: String
        get() = Hex.encode(Sha256.digest(signPublicKey + ecdhPublicKey))

    override fun equals(other: Any?): Boolean =
        other is PairedPeer &&
            other.deviceId == deviceId &&
            other.displayName == displayName &&
            other.signPublicKey.contentEquals(signPublicKey) &&
            other.ecdhPublicKey.contentEquals(ecdhPublicKey) &&
            other.pairedAtMs == pairedAtMs

    override fun hashCode(): Int = deviceId.hashCode()
}

/** Test/demo store — process-local only. */
class InMemoryKeyStore : KeyStore {

    private var identity: StoredIdentity? = null
    private val peers = LinkedHashMap<String, PairedPeer>()

    override fun loadIdentity(): StoredIdentity? = identity

    override fun saveIdentity(identity: StoredIdentity) {
        this.identity = identity
    }

    override fun pairedPeers(): List<PairedPeer> = peers.values.toList()

    override fun savePeer(peer: PairedPeer) {
        peers[peer.deviceId] = peer
    }

    override fun removePeer(deviceId: String) {
        peers.remove(deviceId)
    }
}
