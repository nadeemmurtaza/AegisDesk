package com.newax.aegis.sync

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.security.KeyStore as Jks
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The JVM production identity store (docs/SYNC_DESIGN.md §3, S5) — replaces
 * the dev [FileKeyStore] as the [platformKeyStore] actual on Windows and
 * macOS:
 *
 *  - **Windows: DPAPI.** The identity blob is encrypted with
 *    [Crypt32Util.cryptProtectData] (per-user, machine-bound — only the
 *    current Windows user can decrypt) and stored as Base64 in
 *    `identity.dat`. Same primitive as the platform-impl/windows secrets vault.
 *  - **macOS: Keychain.** A random AES-256 key lives in the login Keychain
 *    (JDK `KeychainStore`); the identity blob is AES-GCM-wrapped with it —
 *    the same wrap format as [AndroidSyncKeyStore] — in `identity.dat`.
 *  - **Linux: dev fallback.** No OS vault is wired yet (libsecret is the
 *    matrix's answer, not this slice) — identity stays in plaintext, exactly
 *    today's [FileKeyStore] behavior.
 *
 * Peers are public-key records — delegated to [FileKeyStore] in the same
 * directory, no secrets. A pre-existing dev identity (`identity.txt`) is read
 * as a fallback so upgrading users keep their device id until the next save
 * writes the protected format.
 *
 * No silent downgrade: if the OS vault is unavailable on Windows/macOS,
 * [saveIdentity] throws (callers treat it like Android's write failure) and
 * [loadIdentity] returns null for a corrupt/lost record — fresh identity, no
 * crash (the same policy as Android).
 */
class OsKeyStore(private val dir: File) : KeyStore {

    companion object {
        private const val KEYCHAIN_ALIAS = "aegis-sync-identity"
        private const val GCM_TAG_BITS = 128
    }

    private val identityFile = File(dir, "identity.dat")
    private val legacyIdentityFile = File(dir, "identity.txt")
    private val peers = FileKeyStore(dir)

    private val os = System.getProperty("os.name").lowercase()

    init {
        dir.mkdirs()
        identityFile.setPermissions()
    }

    override fun loadIdentity(): StoredIdentity? {
        // Legacy dev-store identity (pre-OS-vault) — keep the device id until
        // the next save writes the protected format.
        if (!identityFile.isFile) {
            legacyIdentityFile.readTextOrNull()?.let {
                IdentityCodec.decodeIdentity(it)?.let { legacy -> return legacy }
            }
        }
        val bytes = readFileOrNull(identityFile) ?: return null
        val plain = when {
            os.contains("win") -> dpapiUnprotect(bytes)
            os.contains("mac") -> keychainUnwrap(bytes)
            else -> bytes // Linux dev fallback — plaintext (see class doc)
        } ?: return null
        return IdentityCodec.decodeIdentity(plain.decodeToString())
    }

    override fun saveIdentity(identity: StoredIdentity) {
        val plain = IdentityCodec.encodeIdentity(identity).encodeToByteArray()
        val bytes = when {
            os.contains("win") -> dpapiProtect(plain)
            os.contains("mac") -> keychainWrap(plain)
            else -> plain // Linux dev fallback
        }
        identityFile.writeBytes(bytes)
        identityFile.setPermissions()
    }

    override fun pairedPeers(): List<PairedPeer> = peers.pairedPeers()

    override fun savePeer(peer: PairedPeer) = peers.savePeer(peer)

    override fun removePeer(deviceId: String) = peers.removePeer(deviceId)

    // ── Windows DPAPI (per-user, machine-bound) ─────────────────────────────

    private fun dpapiProtect(plain: ByteArray): ByteArray =
        Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(plain)).encodeToByteArray()

    private fun dpapiUnprotect(bytes: ByteArray): ByteArray? = try {
        Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(bytes))
    } catch (_: Exception) {
        null
    }

    // ── macOS Keychain (KeychainStore-wrapped AES-GCM key) ─────────────────

    private fun keychainKey(): SecretKey {
        val ks = Jks.getInstance("KeychainStore").apply { load(null, null) }
        (ks.getKey(KEYCHAIN_ALIAS, CharArray(0)) as? SecretKey)?.let { return it }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        ks.setKeyEntry(KEYCHAIN_ALIAS, key, CharArray(0), null)
        return key
    }

    private fun keychainWrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keychainKey())
        val nonce = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return ByteArray(4 + nonce.size + ciphertext.size).apply {
            writeInt(this, nonce.size)
            nonce.copyInto(this, 4)
            ciphertext.copyInto(this, 4 + nonce.size)
        }
    }

    private fun keychainUnwrap(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4 + 12 + 16) return null
        val nonceSize = readInt(bytes)
        if (nonceSize < 12 || 4 + nonceSize + 16 > bytes.size) return null
        val nonce = bytes.copyOfRange(4, 4 + nonceSize)
        val ciphertext = bytes.copyOfRange(4 + nonceSize, bytes.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keychainKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun readFileOrNull(file: File): ByteArray? =
        try {
            if (file.isFile && file.length() > 0) file.readBytes() else null
        } catch (_: Exception) {
            null
        }

    private fun File.setPermissions() {
        try {
            setReadable(false, false)
            setReadable(true, true)
            setWritable(false, false)
            setWritable(true, true)
            setExecutable(false, false)
        } catch (_: SecurityException) {
            // best-effort only
        }
    }

    private fun File.readTextOrNull(): String? =
        try {
            if (isFile && length() > 0) readText() else null
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
