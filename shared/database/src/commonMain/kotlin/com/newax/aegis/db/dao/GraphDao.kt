package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.*

@Dao
interface GraphDao {

    // ── Entities ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntity(entity: GraphEntity): Long

    @Query("SELECT * FROM entities WHERE LOWER(canonicalName) = LOWER(:name) LIMIT 1")
    suspend fun findByName(name: String): GraphEntity?

    @Query("SELECT * FROM entities WHERE id = :id LIMIT 1")
    suspend fun entityById(id: Long): GraphEntity?

    @Query("SELECT * FROM entities ORDER BY id DESC LIMIT :limit")
    suspend fun allEntities(limit: Int): List<GraphEntity>

    // ── Aliases ───────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: EntityAlias)

    @Query("SELECT entityId FROM entity_aliases WHERE LOWER(alias) = LOWER(:alias) LIMIT 1")
    suspend fun findEntityByAlias(alias: String): Long?

    @Query("SELECT entityId FROM entity_aliases WHERE LOWER(alias) = LOWER(:alias)")
    suspend fun findEntitiesByAlias(alias: String): List<Long>

    @Query("SELECT * FROM entities WHERE id = :id LIMIT 1")
    suspend fun findEntityById(id: Long): com.newax.aegis.db.entity.GraphEntity?

    // ── Predicates ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPredicate(predicate: GraphPredicate): Long

    @Query("SELECT * FROM predicates WHERE name = :name LIMIT 1")
    suspend fun predicateByName(name: String): GraphPredicate?

    @Query("SELECT * FROM predicates WHERE id = :id LIMIT 1")
    suspend fun predicateById(id: Long): GraphPredicate?

    // ── Edges ─────────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertEdge(edge: GraphEdge): Long

    /** Current edges (not expired) ordered by importance then confidence. */
    @Query("SELECT * FROM edges WHERE subjectId = :subjectId AND validUntil IS NULL ORDER BY importance DESC, confidence DESC")
    suspend fun currentEdgesFrom(subjectId: Long): List<GraphEdge>

    /** All edges including expired history. */
    @Query("SELECT * FROM edges WHERE subjectId = :subjectId ORDER BY createdAt DESC")
    suspend fun allEdgesFrom(subjectId: Long): List<GraphEdge>

    /** Current edges pointing TO a given entity. */
    @Query("SELECT * FROM edges WHERE objectId = :objectId AND validUntil IS NULL ORDER BY importance DESC")
    suspend fun currentEdgesTo(objectId: Long): List<GraphEdge>

    /** All edges pointing TO a given entity (including expired). */
    @Query("SELECT * FROM edges WHERE objectId = :objectId ORDER BY createdAt DESC")
    suspend fun allEdgesTo(objectId: Long): List<GraphEdge>

    @Query("SELECT * FROM edges WHERE subjectId = :subjectId AND predicateId = :predicateId AND validUntil IS NULL")
    suspend fun currentEdgesBySubjectPredicate(subjectId: Long, predicateId: Long): List<GraphEdge>

    /** Expire all current edges with given subject+predicate (sets valid_until = now). */
    @Query("UPDATE edges SET validUntil = :now WHERE subjectId = :subjectId AND predicateId = :predicateId AND validUntil IS NULL")
    suspend fun invalidateEdges(subjectId: Long, predicateId: Long, now: Long): Int

    @Query("SELECT * FROM edges ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentEdges(limit: Int): List<GraphEdge>

    // ── Blobs ─────────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertBlob(blob: GraphBlob): Long

    @Query("SELECT * FROM blobs WHERE id = :id LIMIT 1")
    suspend fun blobById(id: Long): GraphBlob?
}
