package com.newax.aegis.engine.dev.search

import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.engine.ai.MemoryRanker
import com.newax.aegis.engine.embedding.VectorStore
import com.newax.aegis.engine.graph.GraphStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SearchStrategy { EXACT, FTS, GRAPH, TEMPORAL, VECTOR, HYBRID }

data class SearchResult(
    val strategy: SearchStrategy,
    val query: String,
    val candidateCount: Int,
    val results: List<SearchHit>,
    val latencyMs: Long,
    val ramDeltaMb: Long,
    val shortCircuitReason: String? = null,
    val error: String? = null
)

data class SearchHit(val id: Long, val content: String, val subject: String, val score: Float, val source: String)

data class LabComparison(
    val query: String,
    val results: Map<SearchStrategy, SearchResult>,
    val fastestStrategy: SearchStrategy?,
    val mostResultsStrategy: SearchStrategy?,
    val totalMs: Long
)

object SearchLaboratory {

    private fun ramMb() = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)

    suspend fun runExact(query: String, db: NewaxDatabase): SearchResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val ram0 = ramMb()
        return@withContext try {
            val records = db.memoryRecordDao().findBySubject(query, 50)
            val hits = records.map { SearchHit(it.id, it.content, it.subject, it.confidence / 100f, it.source) }
            SearchResult(SearchStrategy.EXACT, query, hits.size, hits, System.currentTimeMillis() - start, ramMb() - ram0)
        } catch (e: Exception) {
            SearchResult(SearchStrategy.EXACT, query, 0, emptyList(), System.currentTimeMillis() - start, 0, error = e.message)
        }
    }

    suspend fun runFts(query: String, db: NewaxDatabase): SearchResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val ram0 = ramMb()
        return@withContext try {
            val records = db.memoryRecordDao().current(1000)
            val terms = query.lowercase().split(" ").filter { it.length > 2 }
            val ranked = records.map { r ->
                val text = r.content.lowercase()
                val score = if (terms.isEmpty()) 0f else terms.count { text.contains(it) }.toFloat() / terms.size
                r to score
            }.filter { it.second > 0f }.sortedByDescending { it.second }.take(20)
            val hits = ranked.map { (r, s) -> SearchHit(r.id, r.content, r.subject, s, r.source) }
            SearchResult(SearchStrategy.FTS, query, hits.size, hits, System.currentTimeMillis() - start, ramMb() - ram0)
        } catch (e: Exception) {
            SearchResult(SearchStrategy.FTS, query, 0, emptyList(), System.currentTimeMillis() - start, 0, error = e.message)
        }
    }

    suspend fun runGraph(query: String, db: NewaxDatabase): SearchResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val ram0 = ramMb()
        return@withContext try {
            val entityId = GraphStore.resolveOrCreate(db, query, GraphStore.EntityType.UNKNOWN)
            val edges = GraphStore.edgesFrom(db, entityId)
            val hits = edges.take(20).map { e ->
                SearchHit(e.id, e.objectValue ?: "edge:${e.objectId}", query, e.confidence / 100f, "graph")
            }
            SearchResult(SearchStrategy.GRAPH, query, hits.size, hits, System.currentTimeMillis() - start, ramMb() - ram0)
        } catch (e: Exception) {
            SearchResult(SearchStrategy.GRAPH, query, 0, emptyList(), System.currentTimeMillis() - start, 0, error = e.message)
        }
    }

    suspend fun runTemporal(query: String, db: NewaxDatabase, lastNDays: Int = 7): SearchResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val ram0 = ramMb()
        return@withContext try {
            val fromMs = System.currentTimeMillis() - lastNDays * 24 * 3600 * 1000L
            val records = db.memoryRecordDao().findByTimeRange(fromMs, System.currentTimeMillis(), 100)
            val queryLower = query.lowercase()
            val filtered = records.filter { it.content.lowercase().contains(queryLower) || it.subject.lowercase().contains(queryLower) }
            val hits = filtered.take(20).map { SearchHit(it.id, it.content, it.subject, it.confidence / 100f, it.source) }
            SearchResult(SearchStrategy.TEMPORAL, query, hits.size, hits, System.currentTimeMillis() - start, ramMb() - ram0)
        } catch (e: Exception) {
            SearchResult(SearchStrategy.TEMPORAL, query, 0, emptyList(), System.currentTimeMillis() - start, 0, error = e.message)
        }
    }

    suspend fun runVector(query: String, db: NewaxDatabase): SearchResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val ram0 = ramMb()
        return@withContext try {
            val results = VectorStore.search(db, query, topK = 20)
            val hits = results.map { r ->
                SearchHit(r.sourceId.toLongOrNull() ?: 0L, r.text, r.sourceType, r.score, "vector:${r.sourceType}")
            }
            SearchResult(SearchStrategy.VECTOR, query, hits.size, hits, System.currentTimeMillis() - start, ramMb() - ram0,
                shortCircuitReason = if (hits.isEmpty()) "Engine not ready or no embeddings indexed" else null)
        } catch (e: Exception) {
            SearchResult(SearchStrategy.VECTOR, query, 0, emptyList(), System.currentTimeMillis() - start, 0,
                shortCircuitReason = "Engine not ready", error = e.message)
        }
    }

    suspend fun runHybrid(query: String, db: NewaxDatabase): SearchResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val ram0 = ramMb()
        return@withContext try {
            val records = db.memoryRecordDao().current(2000)
            val ranked = MemoryRanker.rank(records, query, 20)
            val hits = ranked.map { r -> SearchHit(r.record.id, r.record.content, r.record.subject, r.score, r.record.source) }
            SearchResult(SearchStrategy.HYBRID, query, hits.size, hits, System.currentTimeMillis() - start, ramMb() - ram0)
        } catch (e: Exception) {
            SearchResult(SearchStrategy.HYBRID, query, 0, emptyList(), System.currentTimeMillis() - start, 0, error = e.message)
        }
    }

    suspend fun compareAll(query: String, db: NewaxDatabase): LabComparison {
        val overall = System.currentTimeMillis()
        val all = mapOf(
            SearchStrategy.EXACT to runExact(query, db),
            SearchStrategy.FTS to runFts(query, db),
            SearchStrategy.GRAPH to runGraph(query, db),
            SearchStrategy.TEMPORAL to runTemporal(query, db),
            SearchStrategy.VECTOR to runVector(query, db),
            SearchStrategy.HYBRID to runHybrid(query, db)
        )
        return LabComparison(
            query = query,
            results = all,
            fastestStrategy = all.minByOrNull { it.value.latencyMs }?.key,
            mostResultsStrategy = all.maxByOrNull { it.value.candidateCount }?.key,
            totalMs = System.currentTimeMillis() - overall
        )
    }

    fun formatComparison(comp: LabComparison): String = buildString {
        append("Search Lab: \"${comp.query}\" (total=${comp.totalMs}ms)\n")
        append("  Fastest: ${comp.fastestStrategy}  Most results: ${comp.mostResultsStrategy}\n")
        for ((strategy, result) in comp.results) {
            val status = if (result.error != null) "ERR:${result.error?.take(30)}" else "${result.candidateCount} hits"
            val skip = result.shortCircuitReason?.let { " [${it.take(30)}]" } ?: ""
            append("  $strategy: $status in ${result.latencyMs}ms${skip}\n")
        }
    }
}
