package com.newax.aegis.engine.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

object FileIntelligence {

    private val FILE_PROJECTION = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.RELATIVE_PATH
    )

    // ── Search ────────────────────────────────────────────────────────────────

    fun search(
        context: Context,
        query: String,
        mimeFilter: String? = null,
        limit: Int = 10
    ): List<FileRecord> {
        val queryLower = query.lowercase().trim()
        val selection = buildString {
            append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            if (mimeFilter != null) append(" AND ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?")
        }
        val selArgs = if (mimeFilter != null)
            arrayOf("%$queryLower%", "%$mimeFilter%")
        else
            arrayOf("%$queryLower%")

        return queryMediaStore(context, selection, selArgs, limit)
    }

    fun recentFiles(context: Context, limit: Int = 20, mimeFilter: String? = null): List<FileRecord> {
        val selection = if (mimeFilter != null) "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?" else null
        val selArgs   = if (mimeFilter != null) arrayOf("%$mimeFilter%") else null
        return queryMediaStore(context, selection, selArgs, limit,
            sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")
    }

    fun recentDocuments(context: Context, limit: Int = 10): List<FileRecord> {
        val docMimes = listOf("application/pdf","application/msword","application/vnd.openxmlformats","text/plain","application/vnd.ms-excel","application/vnd.oasis")
        val selection = docMimes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?" }
        val selArgs   = docMimes.map { "%$it%" }.toTypedArray()
        return queryMediaStore(context, selection, selArgs, limit,
            sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")
    }

    fun recentImages(context: Context, limit: Int = 10): List<FileRecord> =
        recentFiles(context, limit, "image/")

    /** Smart search: tries by name first, falls back to recent if blank query */
    fun findBest(context: Context, query: String, limit: Int = 5): List<FileRecord> {
        if (query.isBlank()) return recentFiles(context, limit)
        val results = search(context, query, limit = limit)
        if (results.isNotEmpty()) return results
        // Try word-by-word
        val words = query.split(Regex("\\s+")).filter { it.length > 2 }
        for (word in words) {
            val r = search(context, word, limit = limit)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    // ── Share intent building ─────────────────────────────────────────────────

    fun buildShareIntent(
        file: FileRecord,
        targetPackage: String? = null,
        extraText: String? = null
    ): Intent {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = file.mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, file.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!extraText.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, extraText)
            if (targetPackage != null) setPackage(targetPackage)
        }
        return i
    }

    fun buildSendIntent(file: FileRecord, targetPackage: String? = null): Intent =
        buildShareIntent(file, targetPackage)

    // ── URI info ──────────────────────────────────────────────────────────────

    fun infoFromUri(context: Context, uri: Uri): FileRecord? {
        return runCatching {
            val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
            cursor.use {
                if (!it.moveToFirst()) return null
                val name = it.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { i -> i >= 0 }
                    ?.let { i -> it.getString(i) } ?: uri.lastPathSegment ?: "file"
                val size = it.getColumnIndex(OpenableColumns.SIZE).takeIf { i -> i >= 0 }
                    ?.let { i -> it.getLong(i) } ?: 0L
                val mime = context.contentResolver.getType(uri) ?: "*/*"
                FileRecord(uri, name, mime, size, System.currentTimeMillis())
            }
        }.getOrNull()
    }

    // ── MediaStore query ──────────────────────────────────────────────────────

    private fun queryMediaStore(
        context: Context,
        selection: String?,
        selArgs: Array<String>?,
        limit: Int,
        sortOrder: String = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
    ): List<FileRecord> {
        val results = mutableListOf<FileRecord>()
        val uri = MediaStore.Files.getContentUri("external")
        runCatching {
            context.contentResolver.query(uri, FILE_PROJECTION, selection, selArgs, "$sortOrder LIMIT $limit")
                ?.use { cursor ->
                    val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    val pathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                    while (cursor.moveToNext() && results.size < limit) {
                        val id   = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val mime = cursor.getString(mimeCol) ?: "*/*"
                        val size = cursor.getLong(sizeCol)
                        val date = cursor.getLong(dateCol) * 1000L
                        val path = if (pathCol >= 0) cursor.getString(pathCol) ?: "" else ""
                        val contentUri = Uri.withAppendedPath(uri, id.toString())
                        results += FileRecord(contentUri, name, mime, size, date, path)
                    }
                }
        }
        return results
    }

    // ── Describe for LLM / response text ─────────────────────────────────────

    fun describeResults(files: List<FileRecord>): String {
        if (files.isEmpty()) return "No files found."
        return files.joinToString("\n") { f ->
            "• ${f.name} (${f.humanSize}, ${java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()).format(java.util.Date(f.lastModifiedMs))})"
        }
    }
}
