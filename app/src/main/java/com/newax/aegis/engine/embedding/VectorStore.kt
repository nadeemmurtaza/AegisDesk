package com.newax.aegis.engine.embedding

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.EmbeddingEntity
import com.newax.aegis.db.entity.TripleEntity
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
    private const val THRESHOLD = 0.35f

    data class SearchResult(
        val sourceType: String,
        val sourceId: String,
        val text: String,
        val score: Float
    )

    /** Index a person fact by its DB row ID. No-op if engine not ready. */
    fun indexFact(db: AegisDatabase, factId: Long, text: String) {
        val emb = EmbeddingEngine.embed(text) ?: return
        db.embeddingDao().upsert(
            EmbeddingEntity(
                sourceType = TYPE_FACT,
                sourceId   = factId.toString(),
                text       = text,
                embedding  = emb.toByteArray()
            )
        )
    }

    /** Index a knowledge graph triple as "subject predicate object" text. */
    fun indexTriple(db: AegisDatabase, tripleId: Long, triple: TripleEntity) {
        val text = "${triple.subject} ${triple.predicate.replace('_', ' ')} ${triple.objectValue}"
        val emb = EmbeddingEngine.embed(text) ?: return
        db.embeddingDao().upsert(
            EmbeddingEntity(
                sourceType = TYPE_TRIPLE,
                sourceId   = tripleId.toString(),
                text       = text,
                embedding  = emb.toByteArray()
            )
        )
    }

    /** Index a memory fact stored in EncryptedMemory (keyed by category + content hash). */
    fun indexMemoryFact(db: AegisDatabase, category: String, fact: String) {
        val emb = EmbeddingEngine.embed(fact) ?: return
        db.embeddingDao().upsert(
            EmbeddingEntity(
                sourceType = TYPE_MEMORY,
                sourceId   = "memory:$category:${fact.hashCode()}",
                text       = fact,
                embedding  = emb.toByteArray()
            )
        )
    }

    /**
     * Semantic search across all indexed embeddings.
     * Returns empty list if [EmbeddingEngine] not ready (graceful degradation).
     */
    fun search(db: AegisDatabase, query: String, topK: Int = 6): List<SearchResult> {
        if (!EmbeddingEngine.isReady()) return emptyList()
        val queryEmb = EmbeddingEngine.embed(query) ?: return emptyList()
        val all = db.embeddingDao().getAll()
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

    fun pruneOrphans(db: AegisDatabase) = db.embeddingDao().pruneOrphans()

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
