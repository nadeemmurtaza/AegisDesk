package com.newax.aegis.engine.dev.files

import android.content.Context
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.files.FileIndexer
import com.newax.aegis.engine.files.PHasher
import com.newax.aegis.engine.files.TextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FileIndexStats(
    val total: Int,
    val unindexed: Int,
    val needsText: Int,
    val needsEntity: Int,
    val needsVisual: Int,
    val textExtracted: Int,
    val entityLinked: Int,
    val duplicates: Int
)

data class ExtractionTestResult(
    val path: String,
    val mimeType: String,
    val success: Boolean,
    val text: String,
    val wordCount: Int,
    val pageCount: Int,
    val latencyMs: Long,
    val error: String? = null
)

data class PHashTestResult(
    val pathA: String,
    val pathB: String,
    val hashA: String,
    val hashB: String,
    val hammingDistance: Int,
    val isSimilar: Boolean,
    val threshold: Int
)

data class DuplicateGroup(
    val hash: String,
    val count: Int,
    val paths: List<String>
)

data class PipelineState(
    val stats: FileIndexStats,
    val observerActive: Boolean,
    val pendingScan: Boolean
)

object FileIntelligenceTool {

    suspend fun getStats(db: AegisDatabase): FileIndexStats = withContext(Dispatchers.IO) {
        val dao = db.fileDao()
        FileIndexStats(
            total = dao.totalFiles(),
            unindexed = dao.unindexedCount(),
            needsText = dao.needsTextExtractionCount(),
            needsEntity = dao.needsEntityExtractionCount(),
            needsVisual = dao.needsVisualIndexCount(),
            textExtracted = dao.textContentCount(),
            entityLinked = dao.entityLinkCount(),
            duplicates = dao.duplicateCount()
        )
    }

    suspend fun reindexFile(path: String, context: Context, db: AegisDatabase): String = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext "File not found: $path"
        val dao = db.fileDao()
        val existing = dao.byPath(path)
        if (existing != null) {
            dao.updateIndexState(existing.id, 0)
        }
        FileIndexer.scanDelta(context, db, 0L)
        "Reindex queued for: $path"
    }

    suspend fun testExtraction(path: String): ExtractionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val file = File(path)
        val mime = guessMime(path)
        return@withContext try {
            if (!file.exists()) {
                ExtractionTestResult(path, mime, false, "", 0, 0, System.currentTimeMillis() - start, "File not found")
            } else {
                val result = TextExtractor.extract(file, mime)
                if (result != null) {
                    ExtractionTestResult(
                        path = path, mimeType = mime, success = true,
                        text = result.text.take(500),
                        wordCount = result.text.split("\\s+".toRegex()).size,
                        pageCount = result.pageCount,
                        latencyMs = System.currentTimeMillis() - start
                    )
                } else {
                    ExtractionTestResult(path, mime, false, "", 0, 0, System.currentTimeMillis() - start, "Extractor returned null")
                }
            }
        } catch (e: Exception) {
            ExtractionTestResult(path, mime, false, "", 0, 0, System.currentTimeMillis() - start, e.message)
        }
    }

    suspend fun testPHash(pathA: String, pathB: String, threshold: Int = 10): PHashTestResult = withContext(Dispatchers.IO) {
        val hashA = PHasher.hash(pathA) ?: "ERROR"
        val hashB = PHasher.hash(pathB) ?: "ERROR"
        val distance = if (hashA != "ERROR" && hashB != "ERROR") PHasher.hamming(hashA, hashB) else -1
        PHashTestResult(pathA, pathB, hashA, hashB, distance, distance in 0 until threshold, threshold)
    }

    suspend fun findDuplicates(db: AegisDatabase, limit: Int = 20): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val rawDb = db.openHelper.readableDatabase
        val groups = mutableListOf<DuplicateGroup>()
        runCatching {
            val cursor = rawDb.query(
                "SELECT contentHash, COUNT(*) as cnt FROM file_records WHERE contentHash IS NOT NULL AND contentHash != '' GROUP BY contentHash HAVING cnt > 1 ORDER BY cnt DESC LIMIT $limit",
                null
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val hash = c.getString(0)
                    val count = c.getInt(1)
                    val paths = db.fileDao().byHash(hash).map { it.path }
                    groups.add(DuplicateGroup(hash, count, paths))
                }
            }
        }
        groups
    }

    suspend fun resolveDuplicate(keepPath: String, deletePath: String, db: AegisDatabase): String = withContext(Dispatchers.IO) {
        val toDelete = db.fileDao().byPath(deletePath) ?: return@withContext "Not found: $deletePath"
        val canonical = db.fileDao().byPath(keepPath) ?: return@withContext "Not found: $keepPath"
        db.fileDao().markDuplicate(toDelete.id, canonical.id)
        "Marked ${toDelete.id} as duplicate of ${canonical.id}"
    }

    fun pipelineState(context: Context, db: AegisDatabase): PipelineState {
        val stats = runCatching {
            val dao = db.fileDao()
            FileIndexStats(dao.totalFiles(), dao.unindexedCount(), dao.needsTextExtractionCount(), dao.needsEntityExtractionCount(), dao.needsVisualIndexCount(), dao.textContentCount(), dao.entityLinkCount(), dao.duplicateCount())
        }.getOrDefault(FileIndexStats(0, 0, 0, 0, 0, 0, 0, 0))
        return PipelineState(stats, observerActive = true, pendingScan = stats.unindexed > 0)
    }

    private fun guessMime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "txt", "md", "log" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4", "mkv" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}
