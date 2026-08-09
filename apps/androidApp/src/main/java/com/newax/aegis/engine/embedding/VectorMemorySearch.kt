package com.newax.aegis.engine.embedding

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.memory.EncryptedMemory

/**
 * Unified memory retrieval: semantic vector search over person facts + BM25 over
 * EncryptedMemory facts, merged and deduplicated.
 *
 * If [EmbeddingEngine] is not ready, falls back to pure BM25 (identical to the
 * existing [EncryptedMemory.relevant] behaviour — zero regression).
 */
object VectorMemorySearch {

    /**
     * Returns up to [limit] relevant memory snippets for [query].
     * Vector results appear first (higher semantic recall), BM25 fills the rest.
     */
    fun search(
        db: AegisDatabase,
        memory: EncryptedMemory,
        query: String,
        limit: Int = 8
    ): List<String> {
        val bm25 = memory.relevant(query, limit)

        if (!EmbeddingEngine.isReady()) return bm25

        val vector = VectorStore.search(db, query, topK = limit).map { it.text }

        val seen   = LinkedHashSet<String>(limit * 2)
        val merged = mutableListOf<String>()
        for (text in vector + bm25) {
            val key = text.take(120)
            if (seen.add(key)) {
                merged.add(text)
                if (merged.size >= limit) break
            }
        }
        return merged
    }
}
