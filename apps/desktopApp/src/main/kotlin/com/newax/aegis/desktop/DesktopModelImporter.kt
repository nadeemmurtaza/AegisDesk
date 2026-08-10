package com.newax.aegis.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Desktop-side model importer — the counterpart to Android's [ModelImporter].
 *
 * Operates on the local filesystem (no ContentResolver, no SharedPreferences):
 * discovers .gguf files under ~/.aegis/models/, validates GGUF magic, and computes
 * a SHA-256 fingerprint so [GgufModelProvider]'s [ModelDescriptor] carries the
 * verification hash.
 */
data class DesktopImportedModel(
    val file: File,
    val sha256: String,
    val bytes: Long,
)

object DesktopModelImporter {

    private const val GGUF_MAGIC = "GGUF"
    private const val MAGIC_LEN = 4
    private const val MIN_MODEL_BYTES = 50_000_000L // 50 MB — smallest possible GGUF

    /** The default location the app checks for model files on startup. */
    fun defaultModelDir(): File {
        val home = System.getProperty("user.home")
        return File(home, ".aegis/models").also { it.mkdirs() }
    }

    /**
     * Scans [dir] for .gguf files and returns validated import records for each.
     * Files that fail validation (wrong magic, too small, unreadable) are silently
     * skipped — the caller sees only the successfully imported models.
     */
    fun discover(dir: File = defaultModelDir()): List<DesktopImportedModel> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.extension.equals("gguf", ignoreCase = true) }
            ?.mapNotNull { file ->
                try {
                    importSync(file)
                } catch (_: Exception) {
                    null // skip invalid files
                }
            } ?: emptyList()
    }

    /**
     * Full import: validates the file, computes SHA-256, returns the import record.
     */
    suspend fun importAsync(file: File): DesktopImportedModel = withContext(Dispatchers.IO) {
        importSync(file)
    }

    private fun importSync(file: File): DesktopImportedModel {
        require(file.isFile) { "Not a file: $file" }
        require(file.length() >= MIN_MODEL_BYTES) {
            "File too small for a valid GGUF model: ${file.length()} bytes (minimum $MIN_MODEL_BYTES)"
        }

        // Validate GGUF magic bytes
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(MAGIC_LEN)
            require(raf.read(magic) == MAGIC_LEN &&
                String(magic, Charsets.US_ASCII) == GGUF_MAGIC) {
                "Not a valid GGUF file (bad magic) — expected '$GGUF_MAGIC'"
            }
        }

        // Compute SHA-256 hash
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(1 * 1024 * 1024) // 1 MB buffer
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }

        return DesktopImportedModel(file, hash, file.length())
    }
}