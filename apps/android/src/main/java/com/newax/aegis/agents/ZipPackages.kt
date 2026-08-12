package com.newax.aegis.agents

import android.content.Context
import android.net.Uri
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Shared zip-package reader for the agent + skill import pipelines
 * (docs/AGENTS_DESIGN.md). Untrusted input — every entry name is validated
 * (R12): absolute paths, drive letters, backslashes, and any `..` segment are
 * REJECTED. Bounded too: max entries and max bytes per entry keep a hostile
 * package from exhausting memory. Returns validated (name → bytes) entries
 * (directories skipped), or null on any violation/read failure — the caller
 * decides what the manifest file is and never touches the filesystem until
 * validation has passed.
 */
object ZipPackages {

    private const val MAX_ENTRIES = 256
    private const val MAX_BYTES_PER_ENTRY = 4 * 1024 * 1024

    fun extractValidated(context: Context, uri: Uri): List<Pair<String, ByteArray>>? {
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        val out = mutableListOf<Pair<String, ByteArray>>()
        try {
            ZipInputStream(input).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                var count = 0
                while (entry != null) {
                    if (!entry.isDirectory) {
                        if (++count > MAX_ENTRIES) return null
                        val normalized = entry.name.replace('\\', '/')
                        if (normalized.startsWith("/") || normalized.contains(":") ||
                            normalized.split('/').any { it == ".." }
                        ) {
                            return null
                        }
                        if (entry.size > MAX_BYTES_PER_ENTRY) return null
                        out.add(normalized to zis.readBytes())
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { input.close() }
        }
        return out
    }
}
