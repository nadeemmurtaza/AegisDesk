package com.newax.aegis.engine.files

import android.content.Context
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.FileObject
import com.newax.aegis.engine.person.PersonRegistry
import java.util.Calendar

object FileQueryPlanner {

    enum class IndexPath {
        EXACT_NAME,      // "find invoice.pdf"
        EXTENSION,       // "find all PDFs"
        METADATA_DATE,   // "files from last month"
        METADATA_APP,    // "files from WhatsApp"
        ENTITY,          // "files mentioning Ali"
        FTS,             // "document about authentication"
        CONCEPT,         // "invoices", "photos"
        VISUAL,          // "images similar to X"
        GRAPH,           // "files from project NEWAX"
        TEMPORAL,        // "most recent documents"
        COMBINED         // multi-criteria
    }

    data class FileQuery(
        val raw: String,
        val paths: List<IndexPath>,
        val entityHint: String? = null,
        val personHint: String? = null,
        val mimeHint: String? = null,
        val extensionHint: String? = null,
        val conceptHint: String? = null,
        val fromMs: Long? = null,
        val toMs: Long? = null,
        val sourceAppHint: String? = null,
        val exactName: String? = null,
        val ftsQuery: String? = null
    )

    fun plan(query: String): FileQuery {
        val lower = query.lowercase().trim()
        val paths = mutableListOf<IndexPath>()

        // Exact filename? ends with extension
        val exactName = Regex("""(\S+\.\w{2,5})""").find(lower)?.value
        if (exactName != null) {
            paths += IndexPath.EXACT_NAME
        }

        // Extension hint
        val extMap = mapOf(
            "pdf" to "application/pdf",
            "doc" to "application/msword", "docx" to "application/vnd.openxmlformats",
            "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats",
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "png" to "image/png", "mp3" to "audio/mpeg",
            "mp4" to "video/mp4", "txt" to "text/plain"
        )
        val conceptToMime = mapOf(
            "photo" to "image/%", "image" to "image/%", "picture" to "image/%",
            "video" to "video/%", "audio" to "audio/%", "music" to "audio/%",
            "document" to "application/%", "pdf" to "application/pdf",
            "spreadsheet" to "application/vnd.%", "invoice" to "application/pdf",
            "recording" to "audio/%"
        )

        var mimeHint: String? = null
        var extHint: String? = null
        for ((word, mime) in conceptToMime) {
            if (lower.contains(word)) { mimeHint = mime; break }
        }
        for ((ext, _) in extMap) {
            if (lower.contains(ext)) { extHint = ext; break }
        }

        // Concept hint
        val concepts = listOf("invoice", "receipt", "contract", "report", "resume", "screenshot", "recording", "meeting", "photo")
        val conceptHint = concepts.firstOrNull { lower.contains(it) }
        if (conceptHint != null) paths += IndexPath.CONCEPT

        // Temporal hints
        val nowMs = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        var fromMs: Long? = null
        var toMs: Long? = null
        when {
            lower.contains("today") -> {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                fromMs = cal.timeInMillis; toMs = nowMs
            }
            lower.contains("yesterday") -> {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                fromMs = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
                toMs = cal.timeInMillis
            }
            lower.contains("this week") -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                fromMs = cal.timeInMillis; toMs = nowMs
            }
            lower.contains("last week") -> {
                cal.add(Calendar.WEEK_OF_YEAR, -1)
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                fromMs = cal.timeInMillis
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                toMs = cal.timeInMillis
            }
            lower.contains("this month") || lower.contains("last month") -> {
                if (lower.contains("last month")) cal.add(Calendar.MONTH, -1)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                fromMs = cal.timeInMillis
                toMs = nowMs
            }
        }
        if (fromMs != null) paths += IndexPath.METADATA_DATE

        // Source app hint
        val appHints = mapOf(
            "whatsapp" to "com.whatsapp", "telegram" to "org.telegram",
            "gmail" to "com.google.android.gm", "camera" to "com.google.android.camera",
            "chrome" to "com.android.chrome", "drive" to "com.google.android.apps.docs"
        )
        val sourceAppHint = appHints.entries.firstOrNull { lower.contains(it.key) }?.value
        if (sourceAppHint != null) paths += IndexPath.METADATA_APP

        // Person / entity hint (for "from Ali", "sent by Ali")
        val fromRegex = Regex("""(?:from|by|sent by|received from)\s+(\w+)""")
        val personHint = fromRegex.find(lower)?.groupValues?.get(1)
        if (personHint != null) paths += IndexPath.ENTITY

        // FTS for descriptive queries ("document about authentication", "notes on project X")
        val ftsIndicators = listOf("about", "discuss", "mention", "contain", "say", "regarding", "related to", "on topic")
        val ftsQuery = if (ftsIndicators.any { lower.contains(it) }) {
            lower.replace(Regex("(find|show|get|list|open|recent|document|file|pdf|image)"), "").trim()
        } else null
        if (ftsQuery != null) paths += IndexPath.FTS

        // Fallback: recent / temporal
        if (paths.isEmpty() || lower.contains("recent") || lower.contains("latest") || lower.contains("last")) {
            paths += IndexPath.TEMPORAL
        }

        // Determine best primary path order (cheapest first)
        val ordered = listOf(
            IndexPath.EXACT_NAME, IndexPath.EXTENSION, IndexPath.METADATA_DATE,
            IndexPath.METADATA_APP, IndexPath.ENTITY, IndexPath.CONCEPT,
            IndexPath.FTS, IndexPath.GRAPH, IndexPath.TEMPORAL, IndexPath.VISUAL
        ).filter { it in paths }

        return FileQuery(
            raw = query,
            paths = ordered,
            entityHint = personHint,
            personHint = personHint,
            mimeHint = mimeHint,
            extensionHint = extHint,
            conceptHint = conceptHint,
            fromMs = fromMs,
            toMs = toMs,
            sourceAppHint = sourceAppHint,
            exactName = exactName,
            ftsQuery = ftsQuery
        )
    }

    fun execute(q: FileQuery, db: AegisDatabase, context: Context, limit: Int = 10): List<FileObject> {
        val results = mutableListOf<FileObject>()
        val seen = mutableSetOf<Long>()

        fun add(files: List<FileObject>) {
            files.filter { !it.isDuplicate && seen.add(it.id) }.forEach { results += it }
        }

        for (path in q.paths) {
            if (results.size >= limit) break
            when (path) {
                IndexPath.EXACT_NAME -> q.exactName?.let { add(db.fileDao().byNameIgnoreCase(it, limit)) }
                IndexPath.EXTENSION  -> q.extensionHint?.let { add(db.fileDao().byExtension(it, limit)) }
                IndexPath.METADATA_DATE -> {
                    val from = q.fromMs ?: 0L
                    val to   = q.toMs ?: System.currentTimeMillis()
                    val mime = q.mimeHint
                    if (mime != null) add(db.fileDao().byMimeAndDate(mime, from, to, limit))
                    else add(db.fileDao().byModifiedRange(from, to, limit))
                }
                IndexPath.METADATA_APP -> q.sourceAppHint?.let { add(db.fileDao().bySourceApp(it, limit)) }
                IndexPath.ENTITY -> {
                    val label = q.entityHint ?: continue
                    // Try resolving to a graph entity ID for precision
                    val gid = PersonRegistry.resolve(db, label)
                    if (gid != null) add(db.fileDao().filesByGraphEntity(gid, limit))
                    else add(db.fileDao().filesByEntity(label, limit))
                }
                IndexPath.CONCEPT -> q.conceptHint?.let { add(db.fileDao().byConcept(it, limit)) }
                IndexPath.FTS -> q.ftsQuery?.let { add(db.fileDao().searchByText(it, limit)) }
                IndexPath.GRAPH -> {
                    // Handled via ENTITY path above
                }
                IndexPath.TEMPORAL -> {
                    val mime = q.mimeHint
                    if (mime != null) add(db.fileDao().byMimeType(mime, limit))
                    else add(db.fileDao().recentUniqueFiles(limit))
                }
                IndexPath.VISUAL, IndexPath.COMBINED -> {}
            }
        }
        // Final fallback: filename LIKE
        if (results.isEmpty() && q.raw.isNotBlank()) {
            add(db.fileDao().byFilenameLike(q.raw.take(40), limit))
        }
        return results.take(limit)
    }

    /** Combined query: "Send Ali the latest NEWAX invoice" */
    fun executeFileTask(
        db: AegisDatabase,
        context: Context,
        fileQuery: String,
        fromPerson: String? = null,
        fileType: String? = null,
        limit: Int = 3
    ): List<FileObject> {
        val q = plan(fileQuery)
        var results = execute(q, db, context, limit * 3)
        // Filter by person/entity if given
        if (fromPerson != null && results.size > 1) {
            val gid = PersonRegistry.resolve(db, fromPerson)
            if (gid != null) {
                val fromPerson2 = db.fileDao().filesByGraphEntity(gid, limit * 3)
                val intersection = results.filter { f -> fromPerson2.any { it.id == f.id } }
                if (intersection.isNotEmpty()) results = intersection
            }
        }
        return results.take(limit)
    }
}
