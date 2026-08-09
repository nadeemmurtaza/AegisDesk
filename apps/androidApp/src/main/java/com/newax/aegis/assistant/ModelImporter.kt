package com.newax.aegis.assistant

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class ImportedModel(val file: File, val sha256: String, val bytes: Long)

class ModelImporter(private val context: Context) {
    private val modelDir = File(context.filesDir, "models")
    private val prefs = context.getSharedPreferences("aegis_model", Context.MODE_PRIVATE)

    fun current(): File? = prefs.getString("path", null)?.let(::File)?.takeIf { it.isFile }

    suspend fun import(uri: Uri): ImportedModel = withContext(Dispatchers.IO) {
        modelDir.mkdirs()
        val displayName = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "model.litertlm"
        require(displayName.endsWith(".litertlm", true)) { "Select a .litertlm model bundle." }
        val temp = File(modelDir, "importing.litertlm.part")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected model cannot be opened." }
            temp.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        require(temp.length() in 100_000_000L..4_500_000_000L) { "Unexpected model size: ${temp.length()} bytes." }
        FileInputStream(temp).use { stream ->
            val magic = ByteArray(8)
            require(stream.read(magic) == 8 && magic.toString(Charsets.US_ASCII) == "LITERTLM") { "Invalid LiteRT-LM bundle header." }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(temp).use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break else digest.update(buffer, 0, count) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val target = File(modelDir, "active-$hash.litertlm")
        if (target.exists()) check(target.delete())
        check(temp.renameTo(target)) { "Cannot finalize imported model." }
        modelDir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        prefs.edit().putString("path", target.absolutePath).putString("sha256", hash).putString("name", displayName).apply()
        ImportedModel(target, hash, target.length())
    }
}
