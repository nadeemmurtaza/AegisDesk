package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.FileEntityLink
import com.newax.aegis.db.entity.FileObject
import com.newax.aegis.db.entity.FileTextContent
import com.newax.aegis.db.entity.FileTextFts

@Dao
interface FileDao {

    // ── Upsert / insert ───────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: FileObject): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTextContent(content: FileTextContent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFtsRow(fts: FileTextFts)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntityLink(link: FileEntityLink)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntityLinks(links: List<FileEntityLink>)

    @Update
    suspend fun updateFile(file: FileObject)

    @Query("UPDATE file_objects SET indexState = :state WHERE id = :id")
    suspend fun updateIndexState(id: Long, state: Int): Int

    @Query("UPDATE file_objects SET graphEntityId = :gid WHERE id = :id")
    suspend fun setGraphEntityId(id: Long, gid: Long): Int

    @Query("UPDATE file_objects SET pHash = :hash, thumbnailPath = :thumb, indexState = indexState | 4 WHERE id = :id")
    suspend fun updateVisual(id: Long, hash: String, thumb: String?): Int

    @Query("UPDATE file_objects SET embeddingId = :eid, indexState = indexState | 8 WHERE id = :id")
    suspend fun updateEmbedding(id: Long, eid: Long): Int

    @Query("UPDATE file_objects SET lastOpenedMs = :ts WHERE id = :id")
    suspend fun touchOpened(id: Long, ts: Long): Int

    @Query("UPDATE file_objects SET isDuplicate = 1, canonicalId = :canonId WHERE id = :id")
    suspend fun markDuplicate(id: Long, canonId: Long): Int

    // ── Exact index ───────────────────────────────────────────────────────────

    @Query("SELECT * FROM file_objects WHERE filename = :name LIMIT :limit")
    suspend fun byExactName(name: String, limit: Int = 10): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE LOWER(filename) = LOWER(:name) LIMIT :limit")
    suspend fun byNameIgnoreCase(name: String, limit: Int = 10): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE sha256 = :hash LIMIT 5")
    suspend fun byHash(hash: String): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE sha256 = :hash AND isDuplicate = 0 LIMIT 1")
    suspend fun canonicalByHash(hash: String): FileObject?

    @Query("SELECT * FROM file_objects WHERE folder = :folder ORDER BY modifiedMs DESC LIMIT :limit")
    suspend fun byFolder(folder: String, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE extension = :ext ORDER BY modifiedMs DESC LIMIT :limit")
    suspend fun byExtension(ext: String, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): FileObject?

    @Query("SELECT * FROM file_objects WHERE path = :path LIMIT 1")
    suspend fun byPath(path: String): FileObject?

    @Query("SELECT * FROM file_objects WHERE mediaStoreId = :mediaStoreId AND mediaStoreId != 0 LIMIT 1")
    suspend fun byMediaStoreId(mediaStoreId: Long): FileObject?

    @Query("SELECT * FROM file_objects WHERE contentUriString = :uri LIMIT 1")
    suspend fun byContentUri(uri: String): FileObject?

    @Query("SELECT id FROM file_objects WHERE sha256 = :hash")
    suspend fun idsWithHash(hash: String): List<Long>

    // ── Metadata index ────────────────────────────────────────────────────────

    @Query("SELECT * FROM file_objects WHERE modifiedMs BETWEEN :fromMs AND :toMs ORDER BY modifiedMs DESC LIMIT :limit")
    suspend fun byModifiedRange(fromMs: Long, toMs: Long, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE createdMs BETWEEN :fromMs AND :toMs ORDER BY createdMs DESC LIMIT :limit")
    suspend fun byCreatedRange(fromMs: Long, toMs: Long, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE receivedMs BETWEEN :fromMs AND :toMs ORDER BY receivedMs DESC LIMIT :limit")
    suspend fun byReceivedRange(fromMs: Long, toMs: Long, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE sourceApp = :app ORDER BY modifiedMs DESC LIMIT :limit")
    suspend fun bySourceApp(app: String, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE mimeType LIKE :mime ORDER BY modifiedMs DESC LIMIT :limit")
    suspend fun byMimeType(mime: String, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE mimeType LIKE :mime AND modifiedMs BETWEEN :fromMs AND :toMs ORDER BY modifiedMs DESC LIMIT :limit")
    suspend fun byMimeAndDate(mime: String, fromMs: Long, toMs: Long, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE sizeBytes BETWEEN :minBytes AND :maxBytes ORDER BY sizeBytes DESC LIMIT :limit")
    suspend fun bySizeRange(minBytes: Long, maxBytes: Long, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects ORDER BY modifiedMs DESC LIMIT :limit")
    suspend fun recentFiles(limit: Int = 20): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE isDuplicate = 0 ORDER BY modifiedMs DESC LIMIT :limit")
    suspend fun recentUniqueFiles(limit: Int = 20): List<FileObject>

    // ── FTS / BM25 ────────────────────────────────────────────────────────────

    @Query("""
        SELECT f.* FROM file_objects f
        INNER JOIN file_text_fts t ON f.id = t.rowid
        WHERE t.text MATCH :query
        LIMIT :limit
    """)
    suspend fun searchByText(query: String, limit: Int = 20): List<FileObject>

    @Query("SELECT * FROM file_text_content WHERE fileId = :id LIMIT 1")
    suspend fun textContent(id: Long): FileTextContent?

    // ── Entity index ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM file_entity_links WHERE fileId = :id")
    suspend fun entitiesForFile(id: Long): List<FileEntityLink>

    @Query("""
        SELECT f.* FROM file_objects f
        INNER JOIN file_entity_links e ON f.id = e.fileId
        WHERE LOWER(e.entityLabel) = LOWER(:label)
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    suspend fun filesByEntity(label: String, limit: Int = 20): List<FileObject>

    @Query("""
        SELECT f.* FROM file_objects f
        INNER JOIN file_entity_links e ON f.id = e.fileId
        WHERE e.entityType = :type
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    suspend fun filesByEntityType(type: String, limit: Int = 20): List<FileObject>

    @Query("""
        SELECT f.* FROM file_objects f
        INNER JOIN file_entity_links e ON f.id = e.fileId
        WHERE e.graphEntityId = :gid
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    suspend fun filesByGraphEntity(gid: Long, limit: Int = 20): List<FileObject>

    // ── Combined multi-index ──────────────────────────────────────────────────

    @Query("""
        SELECT f.* FROM file_objects f
        INNER JOIN file_entity_links e ON f.id = e.fileId
        WHERE LOWER(e.entityLabel) = LOWER(:entity)
          AND f.mimeType LIKE :mime
          AND f.modifiedMs BETWEEN :fromMs AND :toMs
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    suspend fun byEntityMimeDate(entity: String, mime: String, fromMs: Long, toMs: Long, limit: Int = 10): List<FileObject>

    @Query("""
        SELECT f.* FROM file_objects f
        WHERE LOWER(f.filename) LIKE '%' || LOWER(:q) || '%'
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    suspend fun byFilenameLike(q: String, limit: Int = 20): List<FileObject>

    @Query("""
        SELECT f.* FROM file_objects f
        WHERE LOWER(f.conceptsJson) LIKE '%' || LOWER(:concept) || '%'
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    suspend fun byConcept(concept: String, limit: Int = 20): List<FileObject>

    // ── Visual / perceptual ───────────────────────────────────────────────────

    @Query("SELECT id, pHash FROM file_objects WHERE pHash != '' AND mimeType LIKE 'image/%'")
    suspend fun allPHashes(): List<PHashRow>

    data class PHashRow(val id: Long, val pHash: String)

    // ── Graph index ───────────────────────────────────────────────────────────

    @Query("SELECT * FROM file_objects WHERE graphEntityId = :gid ORDER BY modifiedMs DESC LIMIT :limit")
    suspend fun byGraphEntityId(gid: Long, limit: Int = 20): List<FileObject>

    // ── Indexer queue ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM file_objects WHERE (indexState & 1) = 0 AND (mimeType LIKE 'application/%' OR mimeType LIKE 'text/%') ORDER BY modifiedMs DESC LIMIT :limit")
    suspend fun needsTextExtraction(limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE (indexState & 2) = 0 AND (indexState & 1) != 0 LIMIT :limit")
    suspend fun needsEntityExtraction(limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE (indexState & 4) = 0 AND mimeType LIKE 'image/%' LIMIT :limit")
    suspend fun needsVisualIndex(limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE (indexState & 8) = 0 AND (indexState & 1) != 0 LIMIT :limit")
    suspend fun needsEmbedding(limit: Int = 20): List<FileObject>

    // ── Delete ────────────────────────────────────────────────────────────────

    @Query("DELETE FROM file_objects WHERE path = :path")
    suspend fun deleteByPath(path: String): Int

    @Query("DELETE FROM file_objects WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM file_text_content WHERE fileId = :id")
    suspend fun deleteTextContent(id: Long): Int

    @Query("DELETE FROM file_entity_links WHERE fileId = :id")
    suspend fun deleteEntityLinks(id: Long): Int

    // ── Stats ─────────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM file_objects")
    suspend fun totalFiles(): Int

    @Query("SELECT COUNT(*) FROM file_objects WHERE isDuplicate = 1")
    suspend fun duplicateCount(): Int

    @Query("SELECT COUNT(*) FROM file_objects WHERE indexState = ${FileObject.INDEX_STATE_BARE}")
    suspend fun unindexedCount(): Int

    @Query("SELECT COUNT(*) FROM file_objects WHERE (indexState & 1) = 0 AND (mimeType LIKE 'application/%' OR mimeType LIKE 'text/%')")
    suspend fun needsTextExtractionCount(): Int

    @Query("SELECT COUNT(*) FROM file_objects WHERE (indexState & 2) = 0 AND (indexState & 1) != 0")
    suspend fun needsEntityExtractionCount(): Int

    @Query("SELECT COUNT(*) FROM file_objects WHERE (indexState & 4) = 0 AND mimeType LIKE 'image/%'")
    suspend fun needsVisualIndexCount(): Int

    @Query("SELECT COUNT(*) FROM file_text_content")
    suspend fun textContentCount(): Int

    @Query("SELECT COUNT(*) FROM file_entity_links")
    suspend fun entityLinkCount(): Int
}

// forward ref
private val FileObject.Companion.INDEX_STATE_BARE get() = com.newax.aegis.db.entity.FileObject.INDEX_STATE_BARE
