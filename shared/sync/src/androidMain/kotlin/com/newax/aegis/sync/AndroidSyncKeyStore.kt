package com.newax.aegis.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore as Jks
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Android production identity store (docs/SYNC_DESIGN.md §3): the device
 * identity's private keys are wrapped with an AES-256-GCM key that lives ONLY
 * in the Android Keystore (TEE-backed, never leaves the device) and the
 * wrapped record is stored in app-private storage. Peers are public-key
 * records — plain files, no secrets — in the same [IdentityCodec] format as
 * the JVM dev store, so records survive a store swap.
 *
 * This is the Android actual for [platformKeyStore]; the JVM actual is the
 * dev [FileKeyStore]. Corruption of the wrapped record (or a lost keystore
 * key after a reinstall) yields null from [loadIdentity] — the caller
 * generates a fresh identity, never a crash.
 */
class AndroidSyncKeyStore(context: Context) : KeyStore {

    companion object {
        private const val KEY_ALIAS = "aegis_sync_identity_key"
        private const val GCM_TAG_BITS = 128
    }

    private val dir = File(context.filesDir, "aegis-sync")
    private val identityFile = File(dir, "identity.dat")
    private val peers = FileKeyStore(File(dir, "peers"))

    init {
        dir.mkdirs()
    }

    override fun loadIdentity(): StoredIdentity? {
        val bytes = readFileOrNull(identityFile) ?: return null
        return unwrap(bytes)
    }

    override fun saveIdentity(identity: StoredIdentity) {
        identityFile.writeBytes(wrap(identity))
    }

    override fun pairedPeers(): List<PairedPeer> = peers.pairedPeers()

    override fun savePeer(peer: PairedPeer) = peers.savePeer(peer)

    override fun removePeer(deviceId: String) = peers.removePeer(deviceId)

    // ── keystore-wrapped identity ────────────────────────────────────────────

    private fun key(): SecretKey {
        val ks = Jks.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrap(identity: StoredIdentity): ByteArray {
        val plaintext = IdentityCodec.encodeIdentity(identity).encodeToByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val nonce = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArray(4 + nonce.size + ciphertext.size).apply {
            writeInt(this, nonce.size)
            nonce.copyInto(this, 4)
            ciphertext.copyInto(this, 4 + nonce.size)
        }
    }

    private fun unwrap(bytes: ByteArray): StoredIdentity? {
        if (bytes.size < 4 + 12 + 16) return null
        val nonceSize = readInt(bytes)
        if (nonceSize < 12 || 4 + nonceSize + 16 > bytes.size) return null
        val nonce = bytes.copyOfRange(4, 4 + nonceSize)
        val ciphertext = bytes.copyOfRange(4 + nonceSize, bytes.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            IdentityCodec.decodeIdentity(cipher.doFinal(ciphertext).decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    private fun readFileOrNull(file: File): ByteArray? =
        try {
            if (file.isFile && file.length() > 0) file.readBytes() else null
        } catch (_: Exception) {
            null
        }

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
}

actual fun platformKeyStore(): KeyStore = AndroidSyncKeyStore(AndroidSyncContext.requireContext())
