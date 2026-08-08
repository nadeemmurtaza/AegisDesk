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
    fun upsertFile(file: FileObject): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTextContent(content: FileTextContent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertFtsRow(fts: FileTextFts)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertEntityLink(link: FileEntityLink)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEntityLinks(links: List<FileEntityLink>)

    @Update
    fun updateFile(file: FileObject)

    @Query("UPDATE file_objects SET indexState = :state WHERE id = :id")
    fun updateIndexState(id: Long, state: Int)

    @Query("UPDATE file_objects SET graphEntityId = :gid WHERE id = :id")
    fun setGraphEntityId(id: Long, gid: Long)

    @Query("UPDATE file_objects SET pHash = :hash, thumbnailPath = :thumb, indexState = indexState | 4 WHERE id = :id")
    fun updateVisual(id: Long, hash: String, thumb: String?)

    @Query("UPDATE file_objects SET embeddingId = :eid, indexState = indexState | 8 WHERE id = :id")
    fun updateEmbedding(id: Long, eid: Long)

    @Query("UPDATE file_objects SET lastOpenedMs = :ts WHERE id = :id")
    fun touchOpened(id: Long, ts: Long)

    @Query("UPDATE file_objects SET isDuplicate = 1, canonicalId = :canonId WHERE id = :id")
    fun markDuplicate(id: Long, canonId: Long)

    // ── Exact index ───────────────────────────────────────────────────────────

    @Query("SELECT * FROM file_objects WHERE filename = :name LIMIT :limit")
    fun byExactName(name: String, limit: Int = 10): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE LOWER(filename) = LOWER(:name) LIMIT :limit")
    fun byNameIgnoreCase(name: String, limit: Int = 10): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE sha256 = :hash LIMIT 5")
    fun byHash(hash: String): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE sha256 = :hash AND isDuplicate = 0 LIMIT 1")
    fun canonicalByHash(hash: String): FileObject?

    @Query("SELECT * FROM file_objects WHERE folder = :folder ORDER BY modifiedMs DESC LIMIT :limit")
    fun byFolder(folder: String, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE extension = :ext ORDER BY modifiedMs DESC LIMIT :limit")
    fun byExtension(ext: String, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE id = :id LIMIT 1")
    fun byId(id: Long): FileObject?

    @Query("SELECT * FROM file_objects WHERE path = :path LIMIT 1")
    fun byPath(path: String): FileObject?

    @Query("SELECT * FROM file_objects WHERE mediaStoreId = :mediaStoreId AND mediaStoreId != 0 LIMIT 1")
    fun byMediaStoreId(mediaStoreId: Long): FileObject?

    @Query("SELECT * FROM file_objects WHERE contentUriString = :uri LIMIT 1")
    fun byContentUri(uri: String): FileObject?

    @Query("SELECT id FROM file_objects WHERE sha256 = :hash")
    fun idsWithHash(hash: String): List<Long>

    // ── Metadata index ────────────────────────────────────────────────────────

    @Query("SELECT * FROM file_objects WHERE modifiedMs BETWEEN :fromMs AND :toMs ORDER BY modifiedMs DESC LIMIT :limit")
    fun byModifiedRange(fromMs: Long, toMs: Long, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE createdMs BETWEEN :fromMs AND :toMs ORDER BY createdMs DESC LIMIT :limit")
    fun byCreatedRange(fromMs: Long, toMs: Long, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE receivedMs BETWEEN :fromMs AND :toMs ORDER BY receivedMs DESC LIMIT :limit")
    fun byReceivedRange(fromMs: Long, toMs: Long, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE sourceApp = :app ORDER BY modifiedMs DESC LIMIT :limit")
    fun bySourceApp(app: String, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE mimeType LIKE :mime ORDER BY modifiedMs DESC LIMIT :limit")
    fun byMimeType(mime: String, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE mimeType LIKE :mime AND modifiedMs BETWEEN :fromMs AND :toMs ORDER BY modifiedMs DESC LIMIT :limit")
    fun byMimeAndDate(mime: String, fromMs: Long, toMs: Long, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE sizeBytes BETWEEN :minBytes AND :maxBytes ORDER BY sizeBytes DESC LIMIT :limit")
    fun bySizeRange(minBytes: Long, maxBytes: Long, limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects ORDER BY modifiedMs DESC LIMIT :limit")
    fun recentFiles(limit: Int = 20): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE isDuplicate = 0 ORDER BY modifiedMs DESC LIMIT :limit")
    fun recentUniqueFiles(limit: Int = 20): List<FileObject>

    // ── FTS / BM25 ────────────────────────────────────────────────────────────

    @SkipQueryVerification
    @Query("""
        SELECT f.* FROM file_objects f
        INNER JOIN file_text_fts t ON f.id = t.rowid
        WHERE t.text MATCH :query
        LIMIT :limit
    """)
    fun searchByText(query: String, limit: Int = 20): List<FileObject>

    @Query("SELECT * FROM file_text_content WHERE fileId = :id LIMIT 1")
    fun textContent(id: Long): FileTextContent?

    // ── Entity index ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM file_entity_links WHERE fileId = :id")
    fun entitiesForFile(id: Long): List<FileEntityLink>

    @Query("""
        SELECT f.* FROM file_objects f
        INNER JOIN file_entity_links e ON f.id = e.fileId
        WHERE LOWER(e.entityLabel) = LOWER(:label)
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    fun filesByEntity(label: String, limit: Int = 20): List<FileObject>

    @Query("""
        SELECT f.* FROM file_objects f
        INNER JOIN file_entity_links e ON f.id = e.fileId
        WHERE e.entityType = :type
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    fun filesByEntityType(type: String, limit: Int = 20): List<FileObject>

    @Query("""
        SELECT f.* FROM file_objects f
        INNER JOIN file_entity_links e ON f.id = e.fileId
        WHERE e.graphEntityId = :gid
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    fun filesByGraphEntity(gid: Long, limit: Int = 20): List<FileObject>

    // ── Combined multi-index ──────────────────────────────────────────────────

    @Query("""
        SELECT f.* FROM file_objects f
        INNER JOIN file_entity_links e ON f.id = e.fileId
        WHERE LOWER(e.entityLabel) = LOWER(:entity)
          AND f.mimeType LIKE :mime
          AND f.modifiedMs BETWEEN :fromMs AND :toMs
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    fun byEntityMimeDate(entity: String, mime: String, fromMs: Long, toMs: Long, limit: Int = 10): List<FileObject>

    @Query("""
        SELECT f.* FROM file_objects f
        WHERE LOWER(f.filename) LIKE '%' || LOWER(:q) || '%'
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    fun byFilenameLike(q: String, limit: Int = 20): List<FileObject>

    @Query("""
        SELECT f.* FROM file_objects f
        WHERE LOWER(f.conceptsJson) LIKE '%' || LOWER(:concept) || '%'
        ORDER BY f.modifiedMs DESC LIMIT :limit
    """)
    fun byConcept(concept: String, limit: Int = 20): List<FileObject>

    // ── Visual / perceptual ───────────────────────────────────────────────────

    @Query("SELECT id, pHash FROM file_objects WHERE pHash != '' AND mimeType LIKE 'image/%'")
    fun allPHashes(): List<PHashRow>

    data class PHashRow(val id: Long, val pHash: String)

    // ── Graph index ───────────────────────────────────────────────────────────

    @Query("SELECT * FROM file_objects WHERE graphEntityId = :gid ORDER BY modifiedMs DESC LIMIT :limit")
    fun byGraphEntityId(gid: Long, limit: Int = 20): List<FileObject>

    // ── Indexer queue ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM file_objects WHERE (indexState & 1) = 0 AND (mimeType LIKE 'application/%' OR mimeType LIKE 'text/%') ORDER BY modifiedMs DESC LIMIT :limit")
    fun needsTextExtraction(limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE (indexState & 2) = 0 AND (indexState & 1) != 0 LIMIT :limit")
    fun needsEntityExtraction(limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE (indexState & 4) = 0 AND mimeType LIKE 'image/%' LIMIT :limit")
    fun needsVisualIndex(limit: Int = 50): List<FileObject>

    @Query("SELECT * FROM file_objects WHERE (indexState & 8) = 0 AND (indexState & 1) != 0 LIMIT :limit")
    fun needsEmbedding(limit: Int = 20): List<FileObject>

    // ── Delete ────────────────────────────────────────────────────────────────

    @Query("DELETE FROM file_objects WHERE path = :path")
    fun deleteByPath(path: String)

    @Query("DELETE FROM file_objects WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM file_text_content WHERE fileId = :id")
    fun deleteTextContent(id: Long)

    @Query("DELETE FROM file_entity_links WHERE fileId = :id")
    fun deleteEntityLinks(id: Long)

    // ── Stats ─────────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM file_objects")
    fun totalFiles(): Int

    @Query("SELECT COUNT(*) FROM file_objects WHERE isDuplicate = 1")
    fun duplicateCount(): Int

    @Query("SELECT COUNT(*) FROM file_objects WHERE indexState = ${FileObject.INDEX_STATE_BARE}")
    fun unindexedCount(): Int

    @Query("SELECT COUNT(*) FROM file_objects WHERE (indexState & 1) = 0 AND (mimeType LIKE 'application/%' OR mimeType LIKE 'text/%')")
    fun needsTextExtractionCount(): Int

    @Query("SELECT COUNT(*) FROM file_objects WHERE (indexState & 2) = 0 AND (indexState & 1) != 0")
    fun needsEntityExtractionCount(): Int

    @Query("SELECT COUNT(*) FROM file_objects WHERE (indexState & 4) = 0 AND mimeType LIKE 'image/%'")
    fun needsVisualIndexCount(): Int

    @Query("SELECT COUNT(*) FROM file_text_content")
    fun textContentCount(): Int

    @Query("SELECT COUNT(*) FROM file_entity_links")
    fun entityLinkCount(): Int
}

// forward ref
private val FileObject.Companion.INDEX_STATE_BARE get() = com.newax.aegis.db.entity.FileObject.INDEX_STATE_BARE
