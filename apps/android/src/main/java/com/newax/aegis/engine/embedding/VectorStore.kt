package com.newax.aegis.engine.embedding

import kotlinx.coroutines.runBlocking
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.db.entity.EmbeddingEntity
import com.newax.aegis.db.entity.TripleEntity
import com.newax.aegis.engine.resource.JobPriority
import com.newax.aegis.engine.resource.ResourceClass
import com.newax.aegis.engine.resource.ResourceGovernor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stores and searches text embeddings in the Room DB.
 *
 * Search strategy:
 *   - Load all embeddings into memory (10k × 512 floats ≈ 20 MB — acceptable on mobile)
 *   - Compute cosine similarity against the query embedding
 *   - Return top-K results above a 0.35 relevance threshold
 *
 * Falls back to nothing if [EmbeddingEngine] isn't ready; callers should
 * always combine with BM25 via [VectorMemorySearch].
 */
object VectorStore {

    const val TYPE_FACT   = "fact"
    const val TYPE_MEMORY = "memory"
    const val TYPE_TRIPLE = "triple"
    const val TYPE_EDGE   = "edge"
    const val TYPE_LIBRARY = "library"
    const val TYPE_EPISODE = "episode"
    private const val THRESHOLD = 0.35f

    data class SearchResult(
        val sourceType: String,
        val sourceId: String,
        val text: String,
        val score: Float
    )

    /** Index a person fact by its DB row ID. No-op if engine not ready. */
    fun indexFact(db: NewaxDatabase, factId: Long, text: String) {
        val emb = EmbeddingEngine.embed(text) ?: return
        runBlocking {
            db.embeddingDao().upsert(
                EmbeddingEntity(
                    sourceType = TYPE_FACT,
                    sourceId   = factId.toString(),
                    text       = text,
                    embedding  = emb.toByteArray()
                )
            )
        }
    }

    /** Index a knowledge graph triple as "subject predicate object" text. */
    fun indexTriple(db: NewaxDatabase, tripleId: Long, triple: TripleEntity) {
        val text = "${triple.subject} ${triple.predicate.replace('_', ' ')} ${triple.objectValue}"
        val emb = EmbeddingEngine.embed(text) ?: return
        runBlocking {
            db.embeddingDao().upsert(
                EmbeddingEntity(
                    sourceType = TYPE_TRIPLE,
                    sourceId   = tripleId.toString(),
                    text       = text,
                    embedding  = emb.toByteArray()
                )
            )
        }
    }

    /** Index a memory fact stored in EncryptedMemory (keyed by category + content hash). */
    fun indexMemoryFact(db: NewaxDatabase, category: String, fact: String) {
        val emb = EmbeddingEngine.embed(fact) ?: return
        runBlocking {
            db.embeddingDao().upsert(
                EmbeddingEntity(
                    sourceType = TYPE_MEMORY,
                    sourceId   = "memory:$category:${fact.hashCode()}",
                    text       = fact,
                    embedding  = emb.toByteArray()
                )
            )
        }
    }

    /**
     * Index an ACTIVE library entry (the gated Global Library) — text carries
     * category + title so semantic recall can hit it. Only ACTIVE entries are
     * ever indexed (PENDING/REJECTED never enter the retrievable space).
     */
    fun indexLibrary(db: NewaxDatabase, entryId: String, category: String, title: String, content: String) {
        val emb = EmbeddingEngine.embed("[$category] $title: $content") ?: return
        runBlocking {
            db.embeddingDao().upsert(
                EmbeddingEntity(
                    sourceType = TYPE_LIBRARY,
                    sourceId   = "library:$entryId",
                    text       = "[$category] $title: $content",
                    embedding  = emb.toByteArray()
                )
            )
        }
    }

    /** Drop a library entry from the vector index (reject/tombstone). */
    fun removeLibrary(db: NewaxDatabase, entryId: String) {
        runBlocking { runCatching { db.embeddingDao().deleteBySource(TYPE_LIBRARY, "library:$entryId") } }
    }

    /** Index an episode (chronological, outcome + lesson) for semantic recall. */
    fun indexEpisode(db: NewaxDatabase, episodeId: String, summary: String, lesson: String, outcome: String) {
        val text = if (lesson.isBlank()) summary else "$summary — lesson ($outcome): $lesson"
        val emb = EmbeddingEngine.embed(text) ?: return
        runBlocking {
            db.embeddingDao().upsert(
                EmbeddingEntity(
                    sourceType = TYPE_EPISODE,
                    sourceId   = "episode:$episodeId",
                    text       = text,
                    embedding  = emb.toByteArray()
                )
            )
        }
    }

    /** Drop an episode from the vector index (tombstone). */
    fun removeEpisode(db: NewaxDatabase, episodeId: String) {
        runBlocking { runCatching { db.embeddingDao().deleteBySource(TYPE_EPISODE, "episode:$episodeId") } }
    }

    /**
     * Semantic search across all indexed embeddings.
     * Returns empty list if [EmbeddingEngine] not ready (graceful degradation).
     */
    fun search(db: NewaxDatabase, query: String, topK: Int = 6): List<SearchResult> {
        if (!EmbeddingEngine.isReady()) return emptyList()
        val queryEmb = EmbeddingEngine.embed(query) ?: return emptyList()
        val all = runBlocking { db.embeddingDao().getAll() }
        if (all.isEmpty()) return emptyList()

        return all.mapNotNull { entity ->
            val emb = entity.embedding.toFloatArray()
            if (emb.size != EmbeddingEngine.DIMS) return@mapNotNull null
            val score = EmbeddingEngine.cosineSimilarity(queryEmb, emb)
            if (score >= THRESHOLD) SearchResult(entity.sourceType, entity.sourceId, entity.text, score)
            else null
        }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /** Index a normalized graph edge as "subject predicate object" text. */
    fun indexEdge(db: NewaxDatabase, edgeId: Long, subjectName: String, predicateName: String, objectStr: String) {
        val text = "$subjectName ${predicateName.replace('_', ' ')} $objectStr"
        val emb = EmbeddingEngine.embed(text) ?: return
        runBlocking {
            db.embeddingDao().upsert(
                EmbeddingEntity(
                    sourceType = TYPE_EDGE,
                    sourceId   = edgeId.toString(),
                    text       = text,
                    embedding  = emb.toByteArray()
                )
            )
        }
    }

    fun pruneOrphans(db: NewaxDatabase) = runBlocking { db.embeddingDao().pruneOrphans() }

    // ── Governor-gated async indexing ─────────────────────────────────────────

    fun submitIndexFact(db: NewaxDatabase, factId: Long, text: String) {
        govSubmit("emb-fact-$factId") { indexFact(db, factId, text) }
    }

    fun submitIndexEdge(db: NewaxDatabase, edgeId: Long, subjectName: String, predicateName: String, objectStr: String) {
        govSubmit("emb-edge-$edgeId") { indexEdge(db, edgeId, subjectName, predicateName, objectStr) }
    }

    fun submitIndexMemory(db: NewaxDatabase, category: String, fact: String) {
        govSubmit("emb-mem-${fact.hashCode()}") { indexMemoryFact(db, category, fact) }
    }

    private fun govSubmit(label: String, block: suspend () -> Unit) {
        ResourceGovernor.fire(label = label, resourceClass = ResourceClass.HEAVY,
            priority = JobPriority.P4_EMBEDDINGS, ramBudgetMb = 50, block = block)
    }

    // ── ByteArray ↔ FloatArray ─────────────────────────────────────────────────

    internal fun FloatArray.toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
        forEach { buf.putFloat(it) }
        return buf.array()
    }

    internal fun ByteArray.toFloatArray(): FloatArray {
        val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 4) { buf.getFloat() }
    }
}
