package com.newax.aegis.engine.files

import android.net.Uri

data class FileRecord(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val relativePath: String = ""
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val isDocument: Boolean get() = mimeType.contains("pdf") || mimeType.contains("word") || mimeType.contains("document") || mimeType.contains("spreadsheet") || mimeType.contains("presentation") || extension in setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","odt","ods")
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isAudio: Boolean get() = mimeType.startsWith("audio/")
    val humanSize: String get() = when {
        sizeBytes > 1_048_576 -> "${"%.1f".format(sizeBytes / 1_048_576.0)} MB"
        sizeBytes > 1024      -> "${sizeBytes / 1024} KB"
        else                  -> "$sizeBytes B"
    }
}
