package com.newax.aegis.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import com.newax.aegis.engine.AndroidSecureSettings
import com.newax.aegis.engine.AutomationSettings
import com.newax.aegis.engine.AutomationToggle
import com.newax.aegis.engine.learning.ScanProgress
import com.newax.aegis.memory.EncryptedMemory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Builds, encrypts, exports, imports, and restores Newax backups.
 *
 * Backup flow:
 *   memory + settings → JSON → GZIP → AES-256-GCM (password) → .aeb file
 *
 * Restore flow:
 *   .aeb file → AES-256-GCM decrypt → GUNZIP → JSON → restore each store
 *
 * File extension: .aeb (Newax Encrypted Backup)
 * No data touches disk in plaintext — the encrypted blob is the only output.
 */
object BackupManager {

    const val FILE_EXTENSION = ".aeb"
    private const val PAYLOAD_VERSION = 1

    // ── Build ─────────────────────────────────────────────────────────────────

    fun buildEncryptedBackup(context: Context, memory: EncryptedMemory, password: CharArray): ByteArray {
        val payload = buildPayload(context, memory)
        return BackupCrypto.encrypt(payload, password)
    }

    private fun buildPayload(context: Context, memory: EncryptedMemory): ByteArray {
        val root = JSONObject()
        root.put("v", PAYLOAD_VERSION)
        root.put("ts", System.currentTimeMillis())
        root.put("device", Build.MODEL)

        // ── Encrypted memory ──────────────────────────────────────────────────
        val (memStrings, memSets) = memory.exportAll()
        val memJson = JSONObject()

        val stringsJson = JSONObject()
        memStrings.forEach { (k, v) -> stringsJson.put(k, v) }
        memJson.put("strings", stringsJson)

        val setsJson = JSONObject()
        memSets.forEach { (k, v) -> setsJson.put(k, JSONArray(v.sorted())) }
        memJson.put("string_sets", setsJson)

        root.put("memory", memJson)

        // ── Automation toggles ────────────────────────────────────────────────
        AutomationSettings.init(AndroidSecureSettings(context))
        val autoJson = JSONObject()
        AutomationToggle.entries.forEach { t ->
            autoJson.put(t.key, AutomationSettings.isEnabled(t))
        }
        root.put("automation", autoJson)

        // ── Scan progress ─────────────────────────────────────────────────────
        ScanProgress.init(context)
        root.put("scan", ScanProgress.exportToJson())

        val jsonBytes = root.toString().toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { baos ->
            GZIPOutputStream(baos).use { it.write(jsonBytes) }
        }.toByteArray()
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    fun decryptAndRestore(context: Context, memory: EncryptedMemory, fileBytes: ByteArray, password: CharArray) {
        val payload  = BackupCrypto.decrypt(fileBytes, password)   // throws SecurityException on bad pw
        val jsonStr  = GZIPInputStream(payload.inputStream()).bufferedReader(Charsets.UTF_8).readText()
        val root     = JSONObject(jsonStr)

        restoreMemory(memory, root.optJSONObject("memory"))
        restoreAutomation(context, root.optJSONObject("automation"))
        restoreScanProgress(context, root.optJSONObject("scan"))
    }

    private fun restoreMemory(memory: EncryptedMemory, json: JSONObject?) {
        json ?: return
        val strings = mutableMapOf<String, String>()
        val stringSets = mutableMapOf<String, Set<String>>()

        json.optJSONObject("strings")?.keys()?.forEach { k ->
            strings[k] = json.getJSONObject("strings").getString(k)
        }
        json.optJSONObject("string_sets")?.keys()?.forEach { k ->
            val arr = json.getJSONObject("string_sets").getJSONArray(k)
            stringSets[k] = (0 until arr.length()).map { arr.getString(it) }.toSet()
        }
        memory.importAll(strings, stringSets)
    }

    private fun restoreAutomation(context: Context, json: JSONObject?) {
        json ?: return
        AutomationSettings.init(AndroidSecureSettings(context))
        val map = json.keys().asSequence().associate { k -> k to json.getBoolean(k) }
        AutomationSettings.importAll(map)
    }

    private fun restoreScanProgress(context: Context, json: JSONObject?) {
        json ?: return
        ScanProgress.init(context)
        ScanProgress.importFromJson(json)
    }

    // ── File I/O ─────────────────────────────────────────────────────────────

    fun writeToUri(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("Cannot open output stream for $uri")
    }

    fun readFromUri(context: Context, uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot open input stream for $uri")
    }

    fun suggestedFilename(): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        return "aegis_backup_$ts$FILE_EXTENSION"
    }
}
