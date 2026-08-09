package com.newax.aegis.engine.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.FileObject
import com.newax.aegis.engine.apps.AppCapability
import com.newax.aegis.engine.apps.AppIntelligence
import com.newax.aegis.engine.person.PersonRegistry
import com.newax.aegis.engine.graph.GraphStore
import kotlinx.coroutines.runBlocking

/**
 * Unified registry facade: resolves cross-domain queries involving
 * Files × People × Projects × Apps in the cheapest index path possible.
 */
object WorldRegistry {

    data class FileTaskQuery(
        val fileQuery: String,
        val personIdentifier: String? = null,
        val capability: AppCapability = AppCapability.SEND_FILE,
        val taskContext: String = "default"
    )

    data class FileTaskResult(
        val files: List<FileObject>,
        val personEntityId: Long?,
        val personName: String?,
        val sendIntent: Intent?,
        val packageName: String,
        val requiresConfirm: Boolean
    )

    // ── File resolution ────────────────────────────────────────────────────────

    fun resolveFiles(context: Context, db: AegisDatabase, query: String, limit: Int = 10): List<FileObject> {
        val q = FileQueryPlanner.plan(query)
        return FileQueryPlanner.execute(q, db, context, limit)
    }

    // ── Cross-domain: file + person + app ────────────────────────────────────

    fun resolveFileTask(context: Context, db: AegisDatabase, request: FileTaskQuery): FileTaskResult? {
        // 1. Resolve files
        val files = FileQueryPlanner.executeFileTask(db, context, request.fileQuery, request.personIdentifier, limit = 5)
        if (files.isEmpty()) return null

        // 2. Resolve person if given
        var personEntityId: Long? = null
        var personName: String? = null
        var packageName = ""
        var sendIntent: Intent? = null
        var requiresConfirm = true

        if (request.personIdentifier != null) {
            val task = PersonRegistry.resolveTask(db, context, request.personIdentifier, request.capability, request.taskContext)
            if (task != null) {
                personEntityId = task.personEntityId
                personName = task.personName
                packageName = task.appResolution.packageName
                val pol = task.policy
                requiresConfirm = !pol.canAutoSend || pol.sensitiveActionsRequireConfirm || pol.canShareFiles < 2

                // Build send intent with the best file
                val topFile = files.first()
                sendIntent = FileIntelligence.buildSendIntent(topFile, packageName)
            }
        } else {
            // No person — just return files + generic share
            val res = AppIntelligence.resolve(db, context, request.capability)
            packageName = res?.packageName ?: ""
            sendIntent = files.firstOrNull()?.let { FileIntelligence.buildSendIntent(it, packageName.ifBlank { null }) }
            requiresConfirm = false
        }

        return FileTaskResult(files, personEntityId, personName, sendIntent, packageName, requiresConfirm)
    }

    // ── Graph linking ─────────────────────────────────────────────────────────

    fun linkFileToPerson(db: AegisDatabase, fileId: Long, personIdentifier: String, predicate: String = "sent_by") = runBlocking {
        val personId = PersonRegistry.resolve(db, personIdentifier) ?: return@runBlocking
        val fo = db.fileDao().recentFiles(1).firstOrNull() ?: return@runBlocking
        fo.graphEntityId?.let { fileEntityId ->
            val personNode = db.graphDao().entityById(personId)?.canonicalName ?: return@runBlocking
            GraphStore.saveEdge(db, "file:${fo.filename}", predicate, personNode, "world_registry")
        }
    }

    fun linkFileToProject(db: AegisDatabase, fileId: Long, projectId: String, predicate: String = "belongs_to") = runBlocking {
        val fo = db.fileDao().recentFiles(1).firstOrNull() ?: return@runBlocking
        GraphStore.saveEdge(db, "file:${fo.filename}", predicate, "project:$projectId", "world_registry")
    }

    // ── Find similar images ───────────────────────────────────────────────────

    fun findSimilarImages(db: AegisDatabase, queryHash: String, maxDistance: Int = 10, limit: Int = 10): List<FileObject> = runBlocking {
        val allHashes = db.fileDao().allPHashes()
        val similar = PHasher.findSimilar(queryHash, allHashes.map { Pair(it.id, it.pHash) }, maxDistance, limit)
        similar.mapNotNull { (id, _) -> db.fileDao().byId(id) }
    }

    fun findSimilarImagesById(db: AegisDatabase, fileId: Long, limit: Int = 10): List<FileObject> = runBlocking {
        val fo = db.fileDao().byId(fileId) ?: return@runBlocking emptyList()
        if (fo.pHash.isBlank()) return@runBlocking emptyList()
        findSimilarImages(db, fo.pHash, limit = limit)
    }

    // ── Duplicate report ──────────────────────────────────────────────────────

    fun duplicateSummary(db: AegisDatabase): String = runBlocking {
        val count = db.fileDao().duplicateCount()
        val total = db.fileDao().totalFiles()
        "$count duplicate files found out of $total total."
    }
}
