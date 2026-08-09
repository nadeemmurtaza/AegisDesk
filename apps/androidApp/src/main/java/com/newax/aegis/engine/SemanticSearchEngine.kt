package com.newax.aegis.engine

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.graph.GraphStore
import kotlinx.coroutines.runBlocking
import kotlin.math.ln

object SemanticSearchEngine {

    private const val BM25_K1 = 1.5
    private const val BM25_B  = 0.75

    private val STOP_WORDS = setOf(
        "the", "and", "for", "are", "was", "with", "that", "this", "from", "have", "not"
    )

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && it !in STOP_WORDS }

    /** Full BM25 ranking over communication logs. */
    fun searchCommunicationLogs(query: String, topK: Int = 3): List<LogEntry> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        val logs = CommunicationLog.getAllLogs()
        if (logs.isEmpty()) return emptyList()

        val documentCount = logs.size
        val tokenizedDocs = logs.map { tokenize(it.summary + " " + it.contact) }
        val avgdl = tokenizedDocs.map { it.size }.average().takeIf { it > 0 } ?: 1.0

        val df = mutableMapOf<String, Int>()
        for (doc in tokenizedDocs) {
            for (token in doc.toSet()) df[token] = df.getOrDefault(token, 0) + 1
        }

        val idf = mutableMapOf<String, Double>()
        for ((token, count) in df) {
            idf[token] = ln((documentCount - count + 0.5) / (count + 0.5) + 1.0)
        }

        val scored = logs.zip(tokenizedDocs).map { (log, docTokens) ->
            val dl = docTokens.size.toDouble()
            var score = 0.0
            for (qToken in queryTokens) {
                val tf = docTokens.count { it == qToken }.toDouble()
                if (tf > 0) {
                    val idfVal = idf[qToken] ?: 0.0
                    score += idfVal * (tf * (BM25_K1 + 1)) / (tf + BM25_K1 * (1 - BM25_B + BM25_B * dl / avgdl))
                }
            }
            log to score
        }

        return scored.filter { it.second > 0 }.sortedByDescending { it.second }.take(topK).map { it.first }
    }

    /**
     * Cross-entity search: Room FTS (person facts + triples) first, then BM25 over
     * in-memory sources (CommunicationLog, ProjectTracker, KnowledgeGraph nodes).
     * Room results are SQL-ranked and take priority; BM25 fills remaining slots.
     */
    fun searchAll(query: String, topK: Int = 5): String {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return "Empty query."

        data class Hit(val label: String, val text: String, val preRanked: Boolean = false, var score: Double = 0.0)
        val candidates = mutableListOf<Hit>()
        val db = try { AegisDatabase.get } catch (_: IllegalStateException) { null }

        // Room FTS — person facts (SQL-ranked, highest priority)
        if (db != null) {
            val safe = query.trim().replace("\"", "")
            try {
                runBlocking {
                    db.personFactDao().searchFts(safe, topK * 2).forEach { e ->
                        candidates += Hit("Fact", e.fact, preRanked = true)
                    }
                }
            } catch (_: Exception) {}

            // Normalized graph — resolve entity tokens, walk current edges
            val entityTokens = (queryTokens.map { it.replaceFirstChar { c -> c.uppercase() } } + queryTokens)
                .distinct().take(4)
            entityTokens.forEach { token ->
                val entityId = GraphStore.resolve(db, token) ?: return@forEach
                runBlocking {
                    val entityName = db.graphDao().entityById(entityId)?.canonicalName ?: token
                    db.graphDao().currentEdgesFrom(entityId).take(4).forEach { edge ->
                        val pred = db.graphDao().predicateById(edge.predicateId)?.name ?: return@forEach
                        val obj = when {
                            edge.objectId != null -> {
                                val objectId = edge.objectId
                                if (objectId != null) db.graphDao().entityById(objectId)?.canonicalName ?: edge.objectValue ?: "?"
                                else edge.objectValue ?: "?"
                            }
                            else -> edge.objectValue ?: "?"
                        }
                        candidates += Hit("Graph[$entityName]", "$entityName ${pred.replace('_',' ')} $obj", preRanked = true)
                    }
                }
            }
        }

        // In-memory BM25 sources
        CommunicationLog.getAllLogs().forEach {
            candidates += Hit("CommLog[${it.contact}]", it.summary + " " + it.contact)
        }
        ProjectTracker.getAllProjects().forEach {
            candidates += Hit("Project[${it.id}]", it.id + " " + it.status + " " + it.notes)
        }
        KnowledgeGraph.getAllNodes().forEach { node ->
            candidates += Hit("Node[${node.id}]", node.id + " " + node.properties.values.joinToString(" "))
        }

        if (candidates.isEmpty()) return "No data to search."

        // BM25 over in-memory candidates only
        val toScore = candidates.filter { !it.preRanked }
        if (toScore.isNotEmpty()) {
            val tokenizedDocs = toScore.map { tokenize(it.text) }
            val avgdl = tokenizedDocs.map { it.size }.average().takeIf { it > 0 } ?: 1.0
            val df = mutableMapOf<String, Int>()
            for (doc in tokenizedDocs) for (t in doc.toSet()) df[t] = df.getOrDefault(t, 0) + 1
            val n = toScore.size.toDouble()
            val idf = df.mapValues { (_, c) -> ln((n - c + 0.5) / (c + 0.5) + 1.0) }
            toScore.zip(tokenizedDocs).forEach { (hit, docTokens) ->
                val dl = docTokens.size.toDouble()
                var sc = 0.0
                for (qToken in queryTokens) {
                    val tf = docTokens.count { it == qToken }.toDouble()
                    if (tf > 0) {
                        val idfVal = idf[qToken] ?: 0.0
                        sc += idfVal * (tf * (BM25_K1 + 1)) / (tf + BM25_K1 * (1 - BM25_B + BM25_B * dl / avgdl))
                    }
                }
                hit.score = sc
            }
        }

        // Merge: pre-ranked (SQL order) first, then BM25 > 0; deduplicate by first 80 chars
        val seen = LinkedHashSet<String>()
        val merged = mutableListOf<Hit>()
        candidates.filter { it.preRanked }.forEach { h -> if (seen.add(h.text.take(80))) merged += h }
        candidates.filter { !it.preRanked && it.score > 0 }.sortedByDescending { it.score }
            .forEach { h -> if (seen.add(h.text.take(80))) merged += h }

        if (merged.isEmpty()) return "No matches found for '$query'."
        return merged.take(topK).joinToString("\n") { "${it.label}: ${it.text.take(120)}" }
    }

    /** O(L) prefix search using the TypeaheadTrie. */
    fun instantPrefixSearch(prefix: String): String {
        val ids = TypeaheadTrie.searchPrefix(prefix)
        if (ids.isEmpty()) return "No instant matches found for prefix '$prefix'."

        val result = StringBuilder("Instant Matches for '$prefix':\n")
        for (idStr in ids.take(10)) {
            when {
                idStr.startsWith("NODE:") -> {
                    val id = idStr.substringAfter("NODE:")
                    result.append("Entity Node [$id]: ${KnowledgeGraph.getNodeInfo(id)}\n")
                }
                idStr.startsWith("PROJECT:") -> {
                    val id = idStr.substringAfter("PROJECT:")
                    val proj = ProjectTracker.getProject(id)
                    if (proj != null) result.append("Project [${proj.id}]: ${proj.status} - ${proj.notes}\n")
                }
                idStr.startsWith("LOG:") -> {
                    val id = idStr.substringAfter("LOG:").toLongOrNull() ?: continue
                    val log = CommunicationLog.getAllLogs().find { it.timestamp == id }
                    if (log != null) result.append("CommLog [${log.contact}]: ${log.summary}\n")
                }
                idStr.startsWith("TRIPLE:") -> {
                    val id = idStr.substringAfter("TRIPLE:").toLongOrNull() ?: continue
                    val db = try { AegisDatabase.get } catch (_: IllegalStateException) { null }
                    val t  = db?.let { runBlocking { it.tripleDao().byId(id) } }
                    if (t != null) result.append("Triple [${t.subject}]: ${t.predicate.replace('_', ' ')} → ${t.objectValue}\n")
                }
                idStr.startsWith("ENTITY:") -> {
                    val id = idStr.substringAfter("ENTITY:").toLongOrNull() ?: continue
                    val db = try { AegisDatabase.get } catch (_: IllegalStateException) { null }
                    val e  = db?.let { runBlocking { it.graphDao().entityById(id) } }
                    if (e != null) {
                        runBlocking {
                            val edges = db.graphDao().currentEdgesFrom(id).take(4)
                            val summaries = mutableListOf<String>()
                            for (edge in edges) {
                                val pred = db.graphDao().predicateById(edge.predicateId)?.name ?: "?"
                                val obj  = edge.objectId?.let { db.graphDao().entityById(it)?.canonicalName } ?: edge.objectValue ?: "?"
                                summaries.add("${pred.replace('_', ' ')} $obj")
                            }
                            val summary = summaries.joinToString("; ")
                            result.append("Entity [${e.canonicalName}]: $summary\n")
                        }
                    }
                }
            }
        }
        return result.toString()
    }
}
