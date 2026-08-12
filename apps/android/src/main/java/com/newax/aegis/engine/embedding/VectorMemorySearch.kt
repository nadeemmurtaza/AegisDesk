package com.newax.aegis.engine.embedding

import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.memory.AgentMemory
import com.newax.aegis.memory.EncryptedMemory

/**
 * Unified memory retrieval — the single seam every runtime consumer uses to
 * turn a query into memory snippets for the model prompt:
 *
 *   1. semantic vector search (person facts, memory profile, graph triples/
 *      edges, AND the agent layers — ACTIVE library entries + episodes),
 *   2. BM25 over EncryptedMemory facts,
 *   3. the hierarchical agent memory (docs/MEMORY_DESIGN.md): keyword recall
 *      over the gated library + matching lessons from FAILURE episodes.
 *
 * If [EmbeddingEngine] is not ready, degrades to BM25 + agent memory (identical
 * to the pre-existing [EncryptedMemory.relevant] behaviour for the profile —
 * zero regression). This object is called by MainViewModel's chat/command path,
 * so the assistant body consumes the agent layers at inference time.
 */
object VectorMemorySearch {

    /**
     * Returns up to [limit] relevant memory snippets for [query], deduped:
     * vector results first (higher semantic recall), then BM25, then the agent
     * library + lessons.
     */
    fun search(
        db: NewaxDatabase,
        memory: EncryptedMemory,
        query: String,
        limit: Int = 8
    ): List<String> {
        val bm25 = memory.relevant(query, limit)
        val agent = AgentMemory.recall(query, limit)

        if (!EmbeddingEngine.isReady()) return dedupe(bm25 + agent, limit)

        val vector = VectorStore.search(db, query, topK = limit).map { it.text }
        return dedupe(vector + bm25 + agent, limit)
    }

    private fun dedupe(texts: List<String>, limit: Int): List<String> {
        val seen   = LinkedHashSet<String>(limit * 2)
        val merged = mutableListOf<String>()
        for (text in texts) {
            val key = text.take(120)
            if (seen.add(key)) {
                merged.add(text)
                if (merged.size >= limit) break
            }
        }
        return merged
    }
}
