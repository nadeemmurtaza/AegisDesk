package com.newax.aegis.engine

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
     * Cross-entity BM25 search across logs, projects, and knowledge graph nodes.
     * Returns a combined ranked summary string.
     */
    fun searchAll(query: String, topK: Int = 5): String {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return "Empty query."

        data class Hit(val label: String, val text: String, var score: Double = 0.0)

        val candidates = mutableListOf<Hit>()

        CommunicationLog.getAllLogs().forEach {
            candidates += Hit("CommLog[${it.contact}]", it.summary + " " + it.contact)
        }
        ProjectTracker.getAllProjects().forEach {
            candidates += Hit("Project[${it.id}]", it.id + " " + it.status + " " + it.notes)
        }
        KnowledgeGraph.getAllNodes().forEach { node ->
            val text = node.id + " " + node.properties.values.joinToString(" ")
            candidates += Hit("Node[${node.id}]", text)
        }

        if (candidates.isEmpty()) return "No data to search."

        val avgdl = candidates.map { tokenize(it.text).size }.average().takeIf { it > 0 } ?: 1.0
        val tokenizedDocs = candidates.map { tokenize(it.text) }
        val df = mutableMapOf<String, Int>()
        for (doc in tokenizedDocs) for (t in doc.toSet()) df[t] = df.getOrDefault(t, 0) + 1
        val n = candidates.size.toDouble()
        val idf = df.mapValues { (_, count) -> ln((n - count + 0.5) / (count + 0.5) + 1.0) }

        candidates.zip(tokenizedDocs).forEach { (hit, docTokens) ->
            val dl = docTokens.size.toDouble()
            var score = 0.0
            for (qToken in queryTokens) {
                val tf = docTokens.count { it == qToken }.toDouble()
                if (tf > 0) {
                    val idfVal = idf[qToken] ?: 0.0
                    score += idfVal * (tf * (BM25_K1 + 1)) / (tf + BM25_K1 * (1 - BM25_B + BM25_B * dl / avgdl))
                }
            }
            hit.score = score
        }

        val top = candidates.filter { it.score > 0 }.sortedByDescending { it.score }.take(topK)
        if (top.isEmpty()) return "No matches found for '$query'."
        return top.joinToString("\n") { "${it.label}: ${it.text.take(120)}" }
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
            }
        }
        return result.toString()
    }
}
