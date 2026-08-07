package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.*

@Dao
interface GraphDao {

    // ── Entities ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEntity(entity: GraphEntity): Long

    @Query("SELECT * FROM entities WHERE LOWER(canonicalName) = LOWER(:name) LIMIT 1")
    fun findByName(name: String): GraphEntity?

    @Query("SELECT * FROM entities WHERE id = :id LIMIT 1")
    fun entityById(id: Long): GraphEntity?

    @Query("SELECT * FROM entities ORDER BY id DESC LIMIT :limit")
    fun allEntities(limit: Int): List<GraphEntity>

    // ── Aliases ───────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAlias(alias: EntityAlias)

    @Query("SELECT entityId FROM entity_aliases WHERE LOWER(alias) = LOWER(:alias) LIMIT 1")
    fun findEntityByAlias(alias: String): Long?

    // ── Predicates ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPredicate(predicate: GraphPredicate): Long

    @Query("SELECT * FROM predicates WHERE name = :name LIMIT 1")
    fun predicateByName(name: String): GraphPredicate?

    @Query("SELECT * FROM predicates WHERE id = :id LIMIT 1")
    fun predicateById(id: Long): GraphPredicate?

    // ── Edges ─────────────────────────────────────────────────────────────────

    @Insert
    fun insertEdge(edge: GraphEdge): Long

    /** Current edges (not expired) ordered by importance then confidence. */
    @Query("SELECT * FROM edges WHERE subjectId = :subjectId AND validUntil IS NULL ORDER BY importance DESC, confidence DESC")
    fun currentEdgesFrom(subjectId: Long): List<GraphEdge>

    /** All edges including expired history. */
    @Query("SELECT * FROM edges WHERE subjectId = :subjectId ORDER BY createdAt DESC")
    fun allEdgesFrom(subjectId: Long): List<GraphEdge>

    /** Current edges pointing TO a given entity. */
    @Query("SELECT * FROM edges WHERE objectId = :objectId AND validUntil IS NULL ORDER BY importance DESC")
    fun currentEdgesTo(objectId: Long): List<GraphEdge>

    /** All edges pointing TO a given entity (including expired). */
    @Query("SELECT * FROM edges WHERE objectId = :objectId ORDER BY createdAt DESC")
    fun allEdgesTo(objectId: Long): List<GraphEdge>

    @Query("SELECT * FROM edges WHERE subjectId = :subjectId AND predicateId = :predicateId AND validUntil IS NULL")
    fun currentEdgesBySubjectPredicate(subjectId: Long, predicateId: Long): List<GraphEdge>

    /** Expire all current edges with given subject+predicate (sets valid_until = now). */
    @Query("UPDATE edges SET validUntil = :now WHERE subjectId = :subjectId AND predicateId = :predicateId AND validUntil IS NULL")
    fun invalidateEdges(subjectId: Long, predicateId: Long, now: Long): Int

    @Query("SELECT * FROM edges ORDER BY createdAt DESC LIMIT :limit")
    fun recentEdges(limit: Int): List<GraphEdge>

    // ── Blobs ─────────────────────────────────────────────────────────────────

    @Insert
    fun insertBlob(blob: GraphBlob): Long

    @Query("SELECT * FROM blobs WHERE id = :id LIMIT 1")
    fun blobById(id: Long): GraphBlob?
}
