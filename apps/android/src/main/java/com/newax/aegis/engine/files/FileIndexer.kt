package com.newax.aegis.engine.files

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.runBlocking
import android.provider.MediaStore
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.db.entity.FileEntityLink
import com.newax.aegis.db.entity.FileObject
import com.newax.aegis.db.entity.FileTextContent
import com.newax.aegis.db.entity.FileTextFts
import com.newax.aegis.engine.graph.GraphStore
import com.newax.aegis.engine.person.PersonRegistry
import com.newax.aegis.engine.resource.JobPriority
import com.newax.aegis.engine.resource.ResourceClass
import com.newax.aegis.engine.resource.ResourceGovernor
import java.io.File

object FileIndexer {

    private val THUMBNAIL_DIR_NAME = "aegis_thumbs"

    // ── MediaStore scan ───────────────────────────────────────────────────────

    fun scanAll(context: Context, db: NewaxDatabase) {
        scanMediaStore(context, db, sinceMs = 0)
    }

    fun scanDelta(context: Context, db: NewaxDatabase, sinceMs: Long) {
        scanMediaStore(context, db, sinceMs)
    }

    private fun scanMediaStore(context: Context, db: NewaxDatabase, sinceMs: Long) = runBlocking {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATA
        )
        val selection = if (sinceMs > 0)
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} > ?" else null
        val selArgs = if (sinceMs > 0)
            arrayOf((sinceMs / 1000).toString()) else null

        val uri = MediaStore.Files.getContentUri("external")
        val cursor = runCatching {
            context.contentResolver.query(uri, projection, selection, selArgs,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")
        }.getOrNull() ?: return@runBlocking

        cursor.use { c ->
            val idCol    = c.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val nameCol  = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol  = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol  = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateModCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val dateAddCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
            val pathCol  = c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val dataCol  = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)

            while (c.moveToNext()) {
                val mediaStoreId = if (idCol >= 0) c.getLong(idCol) else continue
                val path = if (dataCol >= 0) c.getString(dataCol) ?: "" else ""
                val name = if (nameCol >= 0) c.getString(nameCol) ?: "" else ""
                val mime = if (mimeCol >= 0) c.getString(mimeCol) ?: "" else ""
                val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                val mod  = if (dateModCol >= 0) c.getLong(dateModCol) * 1000L else 0L
                val add  = if (dateAddCol >= 0) c.getLong(dateAddCol) * 1000L else 0L
                val rel  = if (pathCol >= 0) c.getString(pathCol) ?: "" else ""

                val contentUri = Uri.withAppendedPath(uri, mediaStoreId.toString())
                val contentUriString = contentUri.toString()

                val existing = db.fileDao().byMediaStoreId(mediaStoreId)
                    ?: if (path.isNotBlank()) db.fileDao().byPath(path) else null
                if (existing != null && existing.modifiedMs >= mod) continue

                val ext = name.substringAfterLast('.', "").lowercase()
                val record = FileObject(
                    id = existing?.id ?: 0,
                    path = path,
                    contentUriString = contentUriString,
                    mediaStoreId = mediaStoreId,
                    filename = name,
                    extension = ext,
                    mimeType = mime,
                    sizeBytes = size,
                    createdMs = add,
                    modifiedMs = mod,
                    folder = rel,
                    indexState = existing?.indexState?.let { it and FileObject.INDEX_STATE_VISUAL.inv() } ?: FileObject.INDEX_STATE_BARE
                )
                val fileId = db.fileDao().upsertFile(record)

                // Deduplicate by hash (fast: only if file size matches existing)
                scheduleHashAndDedup(context, db, fileId, path, mime)
            }
        }
    }

    // ── Content Observer for live updates ────────────────────────────────────

    fun startWatching(context: Context, db: NewaxDatabase) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val sinceMs = System.currentTimeMillis() - 60_000L
                ResourceGovernor.fire("file-delta-scan", ResourceClass.LIGHT, JobPriority.P3_INDEXING) {
                    scanDelta(context, db, sinceMs)
                }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"), true, observer
        )
    }

    // ── Indexing pipeline stages ──────────────────────────────────────────────

    /** Stage 0: hash + dedup (immediate, lightweight) */
    private fun scheduleHashAndDedup(context: Context, db: NewaxDatabase, fileId: Long, path: String, mime: String) {
        ResourceGovernor.fire("hash-$fileId", ResourceClass.LIGHT, JobPriority.P3_INDEXING) {
            val f = File(path)
            if (!f.exists() || f.length() > 200_000_000L) return@fire  // skip >200MB
            val hash = PHasher.sha256(path) ?: return@fire
            runBlocking {
                val existing = db.fileDao().byPath(path) ?: return@runBlocking
                db.fileDao().upsertFile(existing.copy(sha256 = hash))
                // Dedup: find other files with same hash
                val sameHash = db.fileDao().idsWithHash(hash)
                if (sameHash.size > 1) {
                    val canonId = sameHash.min()
                    sameHash.filter { it != canonId }.forEach { db.fileDao().markDuplicate(it, canonId) }
                }
                // Register in GraphStore
                registerInGraph(db, fileId, path, mime)
            }
        }
    }

    /** Stage 1: text extraction (idle, LIGHT) */
    fun runTextExtraction(context: Context, db: NewaxDatabase, limit: Int = 20) = runBlocking {
        val files = db.fileDao().needsTextExtraction(limit)
        files.forEach { fo ->
            val f = File(fo.path)
            if (!f.exists()) return@forEach
            val result = TextExtractor.extract(f, fo.mimeType) ?: run {
                db.fileDao().updateIndexState(fo.id, fo.indexState or FileObject.INDEX_STATE_TEXT)
                return@forEach
            }
            if (result.text.isNotBlank()) {
                db.fileDao().upsertTextContent(FileTextContent(fo.id, result.text, result.language, result.pageCount, result.wordCount))
                db.fileDao().upsertFtsRow(FileTextFts(fo.id, result.text))
            }
            db.fileDao().updateIndexState(fo.id, fo.indexState or FileObject.INDEX_STATE_TEXT)
        }
    }

    /** Stage 2: entity extraction (idle, LIGHT) */
    fun runEntityExtraction(db: NewaxDatabase, limit: Int = 20) = runBlocking {
        val files = db.fileDao().needsEntityExtraction(limit)
        files.forEach { fo ->
            val text = db.fileDao().textContent(fo.id)?.text ?: fo.filename
            val entities = extractEntities(text, fo.filename)
            val links = entities.map { (label, type) ->
                FileEntityLink(fo.id, label, type)
            }
            if (links.isNotEmpty()) db.fileDao().insertEntityLinks(links)
            // Update entitiesJson on FileObject
            val labelsJson = "[" + entities.keys.joinToString(",") { "\"$it\"" } + "]"
            db.fileDao().upsertFile(fo.copy(entitiesJson = labelsJson, indexState = fo.indexState or FileObject.INDEX_STATE_ENTITIES))
        }
    }

    /** Stage 3: visual indexing — pHash + thumbnail (idle + charging) */
    fun runVisualIndexing(context: Context, db: NewaxDatabase, limit: Int = 20) = runBlocking {
        val files = db.fileDao().needsVisualIndex(limit)
        val thumbDir = File(context.cacheDir, THUMBNAIL_DIR_NAME).apply { mkdirs() }
        files.forEach { fo ->
            val f = File(fo.path)
            if (!f.exists()) return@forEach
            val hash = PHasher.hash(fo.path)
            val thumbPath = File(thumbDir, "${fo.id}.jpg").absolutePath
            val thumbOk = PHasher.thumbnail(fo.path, thumbPath)
            db.fileDao().updateVisual(fo.id, hash ?: "", if (thumbOk) thumbPath else null)
        }
    }

    // ── Graph integration ─────────────────────────────────────────────────────

    private fun registerInGraph(db: NewaxDatabase, fileId: Long, path: String, mime: String) = runBlocking {
        val filename = File(path).name
        val nodeType = when {
            mime.startsWith("image/") -> GraphStore.EntityType.FILE
            mime.contains("pdf") || mime.contains("document") -> GraphStore.EntityType.FILE
            else -> GraphStore.EntityType.FILE
        }
        val entityId = GraphStore.resolveOrCreate(db, "file:$filename", nodeType)
        db.fileDao().setGraphEntityId(fileId, entityId)
    }

    fun linkFileToEntity(db: NewaxDatabase, fileId: Long, entityId: Long, predicate: String) = runBlocking {
        val fo = db.fileDao().byId(fileId) ?: return@runBlocking
        val fileEntityId = fo.graphEntityId ?: return@runBlocking
        GraphStore.saveEdge(db, "file:${File(fo.path).name}", predicate, entityId.toString(), "file_indexer")
    }

    fun linkFileToEntityByName(db: NewaxDatabase, fileId: Long, entityName: String, predicate: String) = runBlocking {
        val fo = db.fileDao().byId(fileId) ?: return@runBlocking
        GraphStore.saveEdge(db, "file:${File(fo.path).name}", predicate, entityName, "file_indexer")
    }

    // ── Entity extraction (regex + person registry lookup) ────────────────────

    private fun extractEntities(text: String, filename: String): Map<String, String> {
        val entities = mutableMapOf<String, String>()

        // Invoice numbers
        Regex("""(?i)(invoice|inv|bill)[#\s\-_]?(\d{3,})""").findAll(text).forEach {
            entities["Invoice#${it.groupValues[2]}"] = "INVOICE"
        }
        // Phone numbers
        Regex("""\+?\d[\d\s\-]{8,14}\d""").findAll(text).forEach {
            val clean = it.value.filter { c -> c.isDigit() || c == '+' }
            if (clean.length in 10..15) entities[clean] = "PHONE"
        }
        // Email addresses
        Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""").findAll(text).forEach {
            entities[it.value.lowercase()] = "EMAIL"
        }
        // Project keywords from filename
        val projectWords = filename.replace(Regex("[_\\-.]"), " ").split(" ")
            .filter { it.length > 3 && it[0].isUpperCase() }
        projectWords.forEach { entities[it] = "PROJECT" }
        // Type from extension/mime
        val ext = filename.substringAfterLast('.', "").lowercase()
        when (ext) {
            "pdf" -> entities["pdf"] = "KEYWORD"
            "docx", "doc" -> entities["document"] = "KEYWORD"
            "xlsx", "xls" -> entities["spreadsheet"] = "KEYWORD"
        }
        // Concepts from filename tokens
        val conceptMap = mapOf(
            "invoice" to "invoice", "receipt" to "receipt", "contract" to "contract",
            "report" to "report", "resume" to "resume", "cv" to "resume",
            "photo" to "photo", "screenshot" to "screenshot",
            "recording" to "recording", "meeting" to "meeting"
        )
        val lower = (filename + " " + text.take(200)).lowercase()
        conceptMap.forEach { (k, v) -> if (lower.contains(k)) entities[v] = "KEYWORD" }

        return entities
    }

    // ── Opportunistic task registration ───────────────────────────────────────

    fun registerOpportunisticTasks(context: Context, db: NewaxDatabase) {
        com.newax.aegis.engine.resource.OpportunisticScheduler.register {
            runTextExtraction(context, db, 30)
        }
        com.newax.aegis.engine.resource.OpportunisticScheduler.register {
            runEntityExtraction(db, 30)
        }
        com.newax.aegis.engine.resource.OpportunisticScheduler.register {
            runVisualIndexing(context, db, 20)
        }
    }
}
