package com.newax.aegis.desktop

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared CSV export plumbing for the desktop app — the single source of the
 * RFC-4180 escaping rules, the atomic-write strategy, and the `~/.aegis` export
 * dir. Both [AuditExporter] and [PolicyExporter] delegate here so the
 * correctness-sensitive quoting lives exactly once (R10): fields with a comma,
 * quote, or newline are quoted and internal quotes doubled — data is data,
 * never structure (R12).
 */
internal object CsvFiles {

    /** Renders [header] plus [rows] as CSV. Input row order is preserved. */
    fun render(header: List<String>, rows: List<List<String>>): String = buildString {
        append(header.joinToString(",") { csvField(it) }).append("\r\n")
        rows.forEach { row ->
            row.joinTo(this, ",") { csvField(it) }.append("\r\n")
        }
    }

    /** Writes [csv] to [file] atomically (temp + move — a crash mid-write never corrupts an earlier export). */
    @Throws(IOException::class)
    fun write(csv: String, file: Path): Path {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.write(tmp, csv.toByteArray(Charsets.UTF_8))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        return file
    }

    /** The default export directory: `~/.aegis`. */
    fun defaultDir(): Path =
        Paths.get(System.getProperty("user.home") ?: ".", ".aegis")

    /** Local-time file stamp `yyyyMMdd-HHmmss` for export filenames. */
    fun timestamp(): String =
        Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneId.systemDefault())
            .format(EXPORT_TIME_FORMATTER)

    private fun csvField(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private val EXPORT_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
}
