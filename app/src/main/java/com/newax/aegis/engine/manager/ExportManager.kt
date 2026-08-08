package com.newax.aegis.engine.manager

import android.content.Context
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.memory.EncryptedMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat { JSON, CSV, TXT, MARKDOWN }

data class ExportResult(
    val format: ExportFormat,
    val filePath: String,
    val sizeBytes: Long,
    val recordCount: Int,
    val durationMs: Long
)

object ExportManager {

    private val SDF = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    suspend fun exportMemory(
        context: Context,
        memory: EncryptedMemory,
        format: ExportFormat = ExportFormat.JSON
    ): ExportResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val (strings, sets) = memory.exportAll()
        val timestamp = SDF.format(Date())
        val file = File(context.filesDir, "export_memory_$timestamp.${format.name.lowercase()}")

        when (format) {
            ExportFormat.JSON -> {
                val json = JSONObject()
                strings.forEach { (k, v) -> json.put(k, v) }
                sets.forEach { (k, v) -> json.put(k, JSONArray(v.toList())) }
                file.writeText(json.toString(2))
            }
            ExportFormat.CSV -> {
                val sb = StringBuilder("key,type,value\n")
                strings.forEach { (k, v) -> sb.append("${csvEscape(k)},string,${csvEscape(v)}\n") }
                sets.forEach { (k, v) -> sb.append("${csvEscape(k)},set,${csvEscape(v.joinToString("|"))}\n") }
                file.writeText(sb.toString())
            }
            ExportFormat.MARKDOWN -> {
                val sb = StringBuilder("# Memory Export — $timestamp\n\n## Strings\n")
                strings.forEach { (k, v) -> sb.append("- **$k**: $v\n") }
                sb.append("\n## Sets\n")
                sets.forEach { (k, v) -> sb.append("- **$k**: ${v.joinToString(", ")}\n") }
                file.writeText(sb.toString())
            }
            ExportFormat.TXT -> {
                val sb = StringBuilder("MEMORY EXPORT — $timestamp\n\n")
                strings.forEach { (k, v) -> sb.append("$k = $v\n") }
                sets.forEach { (k, v) -> sb.append("$k = [${v.joinToString(", ")}]\n") }
                file.writeText(sb.toString())
            }
        }

        ExportResult(
            format = format,
            filePath = file.absolutePath,
            sizeBytes = file.length(),
            recordCount = strings.size + sets.size,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    suspend fun exportDatabase(
        context: Context,
        db: AegisDatabase,
        tables: List<String> = emptyList(),
        format: ExportFormat = ExportFormat.JSON
    ): ExportResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val timestamp = SDF.format(Date())
        val file = File(context.filesDir, "export_db_$timestamp.${format.name.lowercase()}")

        val allTables = if (tables.isEmpty()) {
            listOf("memory_records", "person_facts", "file_objects", "trigger_rules",
                   "entities", "edges", "commitments", "app_capabilities")
        } else tables

        val rawDb = db.openHelper.readableDatabase
        var totalRows = 0

        when (format) {
            ExportFormat.JSON -> {
                val root = JSONObject()
                for (table in allTables) {
                    runCatching {
                        val cursor = rawDb.query("SELECT * FROM $table LIMIT 1000", null)
                        val cols = Array(cursor.columnCount) { cursor.getColumnName(it) }
                        val rows = JSONArray()
                        while (cursor.moveToNext()) {
                            val row = JSONObject()
                            cols.forEachIndexed { i, col -> row.put(col, cursor.getString(i) ?: "") }
                            rows.put(row)
                        }
                        cursor.close()
                        root.put(table, rows)
                        totalRows += rows.length()
                    }
                }
                file.writeText(root.toString(2))
            }
            ExportFormat.CSV -> {
                file.printWriter().use { pw ->
                    for (table in allTables) {
                        runCatching {
                            pw.println("# TABLE: $table")
                            val cursor = rawDb.query("SELECT * FROM $table LIMIT 1000", null)
                            val cols = Array(cursor.columnCount) { cursor.getColumnName(it) }
                            pw.println(cols.joinToString(",") { csvEscape(it) })
                            while (cursor.moveToNext()) {
                                pw.println(cols.indices.joinToString(",") { i -> csvEscape(cursor.getString(i) ?: "") })
                                totalRows++
                            }
                            cursor.close()
                        }
                    }
                }
            }
            else -> {
                file.printWriter().use { pw ->
                    for (table in allTables) {
                        pw.println("=== $table ===")
                        runCatching {
                            val cursor = rawDb.query("SELECT COUNT(*) FROM $table", null)
                            if (cursor.moveToFirst()) pw.println("${cursor.getLong(0)} rows")
                            cursor.close()
                            totalRows++
                        }
                    }
                }
            }
        }

        ExportResult(
            format = format,
            filePath = file.absolutePath,
            sizeBytes = file.length(),
            recordCount = totalRows,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    fun listExports(context: Context): List<File> =
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith("export_") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun deleteExport(filePath: String): Boolean = File(filePath).delete()

    private fun csvEscape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }
}
