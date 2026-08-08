package com.newax.aegis.engine.graph

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.EntityAlias
import com.newax.aegis.db.entity.GraphBlob
import com.newax.aegis.db.entity.GraphEdge
import com.newax.aegis.db.entity.GraphEntity
import com.newax.aegis.db.entity.GraphPredicate
import com.newax.aegis.db.entity.TripleEntity

object GraphStore {

    // ── Entity type constants ─────────────────────────────────────────────────

    object EntityType {
        const val UNKNOWN      = 0
        const val PERSON       = 1
        const val COMPANY      = 2
        const val PLACE        = 3
        const val PROJECT      = 4
        const val DEVICE       = 5
        const val CONTACT      = 6
        const val APP          = 7
        const val CONVERSATION = 8
        const val MEETING      = 9
        const val CALL         = 10
        const val OBSERVATION  = 11
        const val PREFERENCE   = 12
        const val HABIT        = 13
        const val GOAL         = 14
        const val TOPIC        = 15
        const val SKILL        = 16
        const val FILE         = 17

        fun label(type: Int): String = when (type) {
            PERSON       -> "Person"
            COMPANY      -> "Company"
            PLACE        -> "Place"
            PROJECT      -> "Project"
            DEVICE       -> "Device"
            APP          -> "App"
            CONVERSATION -> "Conversation"
            MEETING      -> "Meeting"
            CALL         -> "Call"
            OBSERVATION  -> "Observation"
            PREFERENCE   -> "Preference"
            HABIT        -> "Habit"
            GOAL         -> "Goal"
            TOPIC        -> "Topic"
            SKILL        -> "Skill"
            FILE         -> "File"
            else         -> "Entity"
        }
    }

    object BlobType {
        const val CONVERSATION = 1
        const val PROFILE      = 2
        const val DOCUMENT     = 3
        const val IMAGE_REF    = 4
    }

    data class HopNode(
        val entityId: Long,
        val entityName: String,
        val entityType: Int,
        val edges: List<GraphEdge>,
        val depth: Int
    )

    data class EdgeIndex(
        val edgeId: Long,
        val subjectName: String,
        val predicateName: String,
        val objectStr: String
    )

    // ── Entity resolution ─────────────────────────────────────────────────────

    /**
     * Find entity by exact canonical name or alias. Returns null if not found.
     * Does NOT create.
     */
    fun resolve(db: AegisDatabase, name: String): Long? {
        val n = name.trim()
        return db.graphDao().findByName(n)?.id
            ?: db.graphDao().findEntityByAlias(n)
    }

    /**
     * Find entity or create a new one. Synchronized to prevent duplicate inserts
     * from concurrent LLM extraction threads.
     */
    @Synchronized
    fun resolveOrCreate(db: AegisDatabase, name: String, type: Int = EntityType.UNKNOWN): Long {
        val n = name.trim()
        db.graphDao().findByName(n)?.let { return it.id }
        db.graphDao().findEntityByAlias(n)?.let { return it }
        return db.graphDao().insertEntity(
            GraphEntity(type = type, canonicalName = n, createdAt = System.currentTimeMillis())
        )
    }

    fun addAlias(db: AegisDatabase, entityId: Long, alias: String) {
        db.graphDao().insertAlias(EntityAlias(entityId = entityId, alias = alias.trim()))
    }

    // ── Predicate resolution ──────────────────────────────────────────────────

    /** Find or create a predicate by name. Synchronized for same reason as entities. */
    @Synchronized
    fun predicate(db: AegisDatabase, name: String): Long {
        val n = name.lowercase().trim().replace(' ', '_')
        return db.graphDao().predicateByName(n)?.id
            ?: db.graphDao().insertPredicate(GraphPredicate(name = n))
    }

    // ── Edge operations ───────────────────────────────────────────────────────

    fun addEdge(
        db: AegisDatabase,
        subjectId: Long,
        predicateName: String,
        objectId: Long? = null,
        objectValue: String? = null,
        confidence: Int = 80,
        importance: Int = 50,
        sourceId: Long? = null
    ): Long {
        val predicateId = predicate(db, predicateName)
        return db.graphDao().insertEdge(
            GraphEdge(
                subjectId   = subjectId,
                predicateId = predicateId,
                objectId    = objectId,
                objectValue = objectValue,
                confidence  = confidence,
                importance  = importance,
                createdAt   = System.currentTimeMillis(),
                sourceId    = sourceId
            )
        )
    }

    /**
     * Expire all current edges with the same subject+predicate and insert a replacement.
     * Preserves history — old edges get valid_until stamped, not deleted.
     */
    fun updateEdge(
        db: AegisDatabase,
        subjectId: Long,
        predicateName: String,
        newObjectId: Long? = null,
        newObjectValue: String? = null,
        confidence: Int = 80
    ): Long {
        val predicateId = predicate(db, predicateName)
        val now = System.currentTimeMillis()
        db.graphDao().invalidateEdges(subjectId, predicateId, now)
        return db.graphDao().insertEdge(
            GraphEdge(
                subjectId   = subjectId,
                predicateId = predicateId,
                objectId    = newObjectId,
                objectValue = newObjectValue,
                confidence  = confidence,
                createdAt   = now,
                validFrom   = now
            )
        )
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    fun edgesFrom(db: AegisDatabase, entityId: Long, includeExpired: Boolean = false): List<GraphEdge> =
        if (includeExpired) db.graphDao().allEdgesFrom(entityId)
        else db.graphDao().currentEdgesFrom(entityId)

    fun edgesTo(db: AegisDatabase, entityId: Long, includeExpired: Boolean = false): List<GraphEdge> =
        if (includeExpired) db.graphDao().allEdgesTo(entityId)
        else db.graphDao().currentEdgesTo(entityId)

    // ── Multi-hop BFS ─────────────────────────────────────────────────────────

    /**
     * Walk the graph from startId up to maxDepth hops.
     * Returns nodes in BFS order with their outgoing edges at each level.
     */
    fun multihop(
        db: AegisDatabase,
        startId: Long,
        maxDepth: Int = 3,
        maxNodes: Int = 20
    ): List<HopNode> {
        val dao = db.graphDao()
        val visited = LinkedHashSet<Long>()
        val result  = mutableListOf<HopNode>()
        val queue   = ArrayDeque<Pair<Long, Int>>()
        queue.addLast(startId to 0)

        while (queue.isNotEmpty() && result.size < maxNodes) {
            val (entityId, depth) = queue.removeFirst()
            if (!visited.add(entityId)) continue
            val entity = dao.entityById(entityId) ?: continue
            val edges  = dao.currentEdgesFrom(entityId).take(8)
            result += HopNode(entityId, entity.canonicalName, entity.type, edges, depth)
            if (depth < maxDepth) {
                edges.forEach { edge ->
                    edge.objectId?.let { oid -> if (oid !in visited) queue.addLast(oid to depth + 1) }
                }
            }
        }
        return result
    }

    // ── LLM context string ────────────────────────────────────────────────────

    /**
     * Build a compact text summary of graph edges for named entities.
     * Used by ContextCorrelator instead of raw tripleDao queries.
     */
    fun contextFor(db: AegisDatabase, entityNames: List<String>): String {
        if (entityNames.isEmpty()) return ""
        val dao = db.graphDao()
        val sb  = StringBuilder()
        for (name in entityNames.take(6)) {
            val entityId = resolve(db, name) ?: continue
            val entity   = dao.entityById(entityId) ?: continue
            val edges    = dao.currentEdgesFrom(entityId).take(8)
            if (edges.isEmpty()) continue
            sb.appendLine("${entity.canonicalName} [${EntityType.label(entity.type)}]:")
            for (edge in edges) {
                val pred   = dao.predicateById(edge.predicateId)?.name ?: continue
                val objStr = when {
                    edge.objectId != null -> dao.entityById(edge.objectId)?.canonicalName ?: edge.objectValue ?: "?"
                    else                  -> edge.objectValue ?: "?"
                }
                val confNote = if (edge.confidence < 70) " [${edge.confidence}%]" else ""
                sb.appendLine("  ${pred.replace('_', ' ')} → $objStr$confNote")
            }
        }
        return sb.toString().trim()
    }

    // ── Save LLM-extracted triples as normalized graph edges ──────────────────

    /**
     * Converts [TripleEntity] objects from LlmTripleExtractor into normalized
     * entities + edges in the graph store.
     * Returns index info for embedding each new edge.
     */
    fun saveLlmTriples(db: AegisDatabase, triples: List<TripleEntity>): List<EdgeIndex> {
        if (triples.isEmpty()) return emptyList()
        val result = mutableListOf<EdgeIndex>()
        for (triple in triples) {
            val subjectId   = resolveOrCreate(db, triple.subject, EntityType.UNKNOWN)
            val predicateId = predicate(db, triple.predicate)

            val (objectId, objectValue) = if (triple.predicate in StandardPredicates.ENTITY_OBJECT) {
                resolveOrCreate(db, triple.objectValue, EntityType.UNKNOWN) to null
            } else {
                null to triple.objectValue
            }

            val edgeId = db.graphDao().insertEdge(
                GraphEdge(
                    subjectId   = subjectId,
                    predicateId = predicateId,
                    objectId    = objectId,
                    objectValue = objectValue,
                    confidence  = (triple.confidence * 100).toInt().coerceIn(0, 100),
                    createdAt   = triple.createdMs
                )
            )
            result += EdgeIndex(edgeId, triple.subject, triple.predicate, triple.objectValue)
        }
        return result
    }

    /**
     * Persist a manually-created directed edge (e.g. from ProposedAction.UpdateGraph).
     * Both endpoints become entities; existing current edges with the same
     * subject+predicate are expired first to maintain temporal correctness.
     */
    fun saveEdge(
        db: AegisDatabase,
        from: String,
        relation: String,
        to: String,
        source: String = "manual"
    ) {
        val subjectId = resolveOrCreate(db, from, EntityType.UNKNOWN)
        val objectId  = resolveOrCreate(db, to,   EntityType.UNKNOWN)
        updateEdge(db, subjectId, relation, newObjectId = objectId, confidence = 100)
    }

    // ── Blob store ────────────────────────────────────────────────────────────

    fun storeBlob(db: AegisDatabase, type: Int, content: String): Long =
        db.graphDao().insertBlob(
            GraphBlob(type = type, content = content, createdAt = System.currentTimeMillis())
        )
}
