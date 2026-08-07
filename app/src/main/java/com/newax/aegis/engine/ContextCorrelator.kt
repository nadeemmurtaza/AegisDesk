package com.newax.aegis.engine

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.memory.EncryptedMemory

/**
 * Extracts entities from text and correlates them with stored memories,
 * communication logs, knowledge graph, and project records to build
 * a rich context packet for the AI engine.
 */
object ContextCorrelator {

    data class ExtractedEntities(
        val names: List<String>,
        val emails: List<String>,
        val phones: List<String>,
        val urls: List<String>,
        val organizations: List<String>,
        val topics: List<String>,
        val numbers: List<String>         // amounts, dates, IDs
    )

    data class ContextPacket(
        val entities: ExtractedEntities,
        val relevantMemories: String,
        val recentCommunications: String,
        val relatedProjects: String,
        val knowledgeNodes: String,
        val combinedSummary: String
    )

    // --- Entity extraction ---

    private val EMAIL_RE = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
    private val PHONE_RE = Regex("(?:\\+?\\d{1,3}[\\s.-]?)?\\(?\\d{2,4}\\)?[\\s.-]?\\d{3,4}[\\s.-]?\\d{4}")
    private val URL_RE   = Regex("https?://[^\\s\"'<>]+")
    private val AMT_RE   = Regex("(?:rs\\.?|pkr|\\$|£|€|¥|₹)\\s*[\\d,]+(?:\\.\\d{1,2})?|\\b\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?\\b", RegexOption.IGNORE_CASE)

    private val ORG_SUFFIXES = setOf(
        "ltd", "limited", "inc", "corp", "corporation", "llc", "pvt", "company",
        "co.", "group", "bank", "university", "institute", "hospital", "ministry",
        "department", "agency", "association", "foundation", "authority"
    )

    private val TOPIC_VOCAB: Map<String, List<String>> = mapOf(
        "finance"    to listOf("payment", "invoice", "transfer", "account", "balance", "transaction", "loan"),
        "health"     to listOf("doctor", "hospital", "medicine", "prescription", "appointment", "diagnosis"),
        "legal"      to listOf("contract", "agreement", "court", "lawsuit", "attorney", "law", "clause"),
        "travel"     to listOf("flight", "booking", "hotel", "visa", "passport", "itinerary", "reservation"),
        "technology" to listOf("software", "update", "password", "account", "server", "database", "api"),
        "security"   to listOf("otp", "verification", "authentication", "suspicious", "alert", "breach"),
        "shopping"   to listOf("order", "delivery", "shipment", "product", "cart", "checkout", "discount")
    )

    fun extractEntities(text: String): ExtractedEntities {
        val lower = text.lowercase()
        val emails = EMAIL_RE.findAll(text).map { it.value }.distinct().toList()
        val phones = PHONE_RE.findAll(text).map { it.value }.distinct().toList()
        val urls = URL_RE.findAll(text).map { it.value }.distinct().toList()
        val amounts = AMT_RE.findAll(text).map { it.value }.distinct().toList()

        val words = text.split(Regex("\\s+"))
        val orgs = mutableListOf<String>()
        for (i in words.indices) {
            val w = words[i].lowercase().trimEnd('.', ',')
            if (w in ORG_SUFFIXES && i > 0) {
                orgs += words.subList(maxOf(0, i - 2), i + 1).joinToString(" ")
            }
        }

        val names = mutableListOf<String>()
        val namePattern = Regex("\\b([A-Z][a-z]+(?:\\s[A-Z][a-z]+){1,2})\\b")
        for (m in namePattern.findAll(text)) {
            if (!orgs.any { it.contains(m.value) } && m.value !in COMMON_CAPS)
                names += m.value
        }

        val topics = TOPIC_VOCAB.entries
            .filter { (_, kws) -> kws.any { lower.contains(it) } }
            .map { it.key }

        return ExtractedEntities(
            names = names.distinct(),
            emails = emails,
            phones = phones,
            urls = urls,
            organizations = orgs.distinct(),
            topics = topics,
            numbers = amounts
        )
    }

    fun buildContext(
        text: String,
        memory: EncryptedMemory,
        commLog: CommunicationLog,
        graph: KnowledgeGraph,
        projects: ProjectTracker
    ): ContextPacket {
        val entities = extractEntities(text)

        // Memory: relevant() returns a flat List<String> of facts
        val facts = memory.relevant(text)
        val memStr = if (facts.isEmpty()) "None"
            else facts.joinToString("\n") { "• $it" }

        val commStr = buildCommContext(entities, commLog)
        val projectStr = buildProjectContext(entities, projects)
        val graphStr = buildGraphContext(entities, graph)
        val combined = buildCombinedSummary(entities, memStr, commStr, projectStr, graphStr)

        return ContextPacket(entities, memStr, commStr, projectStr, graphStr, combined)
    }

    private fun buildCommContext(entities: ExtractedEntities, log: CommunicationLog): String {
        val contacts = (entities.names + entities.emails + entities.phones).toSet()
        if (contacts.isEmpty()) {
            val recent = log.getLogs(limit = 5)
            return if (recent.isEmpty()) "None"
            else recent.joinToString("\n") { "• [${it.source}] ${it.contact}: ${it.message.take(80)}" }
        }
        val sb = StringBuilder()
        for (contact in contacts) {
            val logs = log.getLogsForContact(contact, limit = 3)
            if (logs.isNotEmpty()) {
                sb.appendLine("Contact: $contact")
                logs.forEach { sb.appendLine("  • ${it.direction} ${it.source}: ${it.message.take(100)}") }
            }
        }
        return sb.toString().ifBlank { "None" }
    }

    private fun buildProjectContext(entities: ExtractedEntities, projects: ProjectTracker): String {
        val keywords = (entities.organizations + entities.topics + entities.names).toSet()
        val all = projects.getAllProjects()
        val relevant = all.filter { proj ->
            keywords.any { kw ->
                proj.id.contains(kw, ignoreCase = true) ||
                proj.notes.contains(kw, ignoreCase = true)
            }
        }.take(3)
        return if (relevant.isEmpty()) "None"
        else relevant.joinToString("\n") { "• [${it.status}] ${it.id}: ${it.notes.take(80)}" }
    }

    private fun buildGraphContext(entities: ExtractedEntities, graph: KnowledgeGraph): String {
        val ids = (entities.names + entities.organizations).take(5)
        val sb = StringBuilder()
        val db = try { AegisDatabase.get } catch (_: IllegalStateException) { null }
        for (id in ids) {
            val edges   = graph.query(id)
            val triples = db?.tripleDao()?.involving(id).orEmpty()
            if (edges.isNotEmpty() || triples.isNotEmpty()) {
                sb.appendLine("Node: $id")
                edges.take(4).forEach   { sb.appendLine("  ${it.relation} → ${it.to}") }
                triples.take(4).forEach { t -> sb.appendLine("  ${t.predicate.replace('_', ' ')} → ${t.objectValue}") }
                val props = graph.getNodeInfo(id)
                if (props.isNotBlank() && props != "Node not found." && props != "Node '$id' has no properties.")
                    sb.appendLine("  props: $props")
            }
        }
        return sb.toString().ifBlank { "None" }
    }

    private fun buildCombinedSummary(
        entities: ExtractedEntities,
        memories: String,
        comms: String,
        projectsStr: String,
        graph: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== AEGIS CONTEXT ===")
        if (entities.names.isNotEmpty()) sb.appendLine("People: ${entities.names.joinToString(", ")}")
        if (entities.organizations.isNotEmpty()) sb.appendLine("Orgs: ${entities.organizations.joinToString(", ")}")
        if (entities.topics.isNotEmpty()) sb.appendLine("Topics: ${entities.topics.joinToString(", ")}")
        if (memories != "None") { sb.appendLine("--- Memory ---"); sb.appendLine(memories) }
        if (comms != "None") { sb.appendLine("--- Communications ---"); sb.appendLine(comms) }
        if (projectsStr != "None") { sb.appendLine("--- Projects ---"); sb.appendLine(projectsStr) }
        if (graph != "None") { sb.appendLine("--- Knowledge Graph ---"); sb.appendLine(graph) }
        return sb.toString().trim()
    }

    private val COMMON_CAPS = setOf(
        "I", "A", "The", "An", "In", "On", "At", "To", "For", "Of", "And", "Or",
        "But", "With", "From", "By", "As", "Is", "Are", "Was", "Were", "Be",
        "OK", "Yes", "No", "Hi", "Hey", "Dear", "Mr", "Mrs", "Ms", "Dr", "Sir",
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December"
    )
}
