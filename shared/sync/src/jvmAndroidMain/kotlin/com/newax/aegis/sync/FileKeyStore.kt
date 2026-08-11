package com.newax.aegis.sync

import java.io.File

/**
 * Dev-only key store: identity + paired peers as hex-encoded text files under
 * a directory (default `~/.aegis/keys`). File permissions are tightened
 * best-effort.
 *
 * NOT the production store — the JVM actual for [platformKeyStore] (the
 * Android production store is [AndroidSyncKeyStore], TEE-wrapped); this exists
 * so the seam has a real non-volatile actual and the JVM tests can exercise
 * persistence.
 */
class FileKeyStore(private val dir: File) : KeyStore {

    private val identityFile = File(dir, "identity.txt")
    private val peersDir = File(dir, "peers")

    init {
        dir.mkdirs()
        peersDir.mkdirs()
        identityFile.setPermissions()
    }

    override fun loadIdentity(): StoredIdentity? {
        val line = identityFile.readTextOrNull() ?: return null
        return IdentityCodec.decodeIdentity(line)
    }

    override fun saveIdentity(identity: StoredIdentity) {
        identityFile.writeText(IdentityCodec.encodeIdentity(identity))
        identityFile.setPermissions()
    }

    override fun pairedPeers(): List<PairedPeer> {
        val files = peersDir.listFiles()?.filter { it.isFile && it.extension == "peer" } ?: return emptyList()
        return files.mapNotNull { readPeer(it) }
    }

    override fun savePeer(peer: PairedPeer) {
        val file = File(peersDir, "${peer.deviceId}.peer")
        file.writeText(IdentityCodec.encodePeer(peer))
        file.setPermissions()
    }

    override fun removePeer(deviceId: String) {
        File(peersDir, "$deviceId.peer").delete()
    }

    private fun readPeer(file: File): PairedPeer? {
        val line = file.readTextOrNull() ?: return null
        return IdentityCodec.decodePeer(line)
    }

    private fun File.setPermissions() {
        try {
            setReadable(false, false)
            setReadable(true, true)
            setWritable(false, false)
            setWritable(true, true)
            setExecutable(false, false)
        } catch (_: SecurityException) {
            // best-effort only — dev store
        }
    }

    private fun File.readTextOrNull(): String? =
        try {
            if (isFile && length() > 0) readText() else null
        } catch (e: Exception) {
            null
        }
}
