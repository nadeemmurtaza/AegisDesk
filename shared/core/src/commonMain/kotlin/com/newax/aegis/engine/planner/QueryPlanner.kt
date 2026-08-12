package com.newax.aegis.engine.planner

import com.newax.aegis.currentTimeMillis

object QueryPlanner {

    enum class Intent {
        APP_LAUNCH, EXACT_FACT, ENTITY_PROFILE, CONTACT_LOOKUP, CALENDAR_QUERY,
        PROJECT_STATUS, RELATIONSHIP_QUERY, TEMPORAL_QUERY,
        SEMANTIC_QUERY, HABIT_INFERENCE, MULTI_HOP_GRAPH, GENERAL
    }

    enum class RetrievalPath {
        KV_EXACT, CONTACT_LOOKUP, CALENDAR, FTS_BM25,
        GRAPH_TRAVERSAL, GRAPH_MULTIHOP, VECTOR_SEMANTIC,
        PREFIX_TRIE, TEMPORAL_FILTER, OBJECT_STORE
    }

    data class TemporalFilter(val fromMs: Long, val untilMs: Long)

    data class QueryPlan(
        val intent: Intent,
        val entityNames: List<String>,
        val keywords: List<String>,
        val temporalFilter: TemporalFilter?,
        val paths: List<RetrievalPath>,
        val requiresLlm: Boolean,
        val appCapabilityHint: com.newax.aegis.engine.apps.AppCapability? = null
    )

    fun plan(query: String): QueryPlan {
        val lower    = query.lowercase().trim()
        val entities = extractEntities(query)
        val keywords = tokenize(lower)
        val scores   = mutableMapOf<Intent, Int>()

        if (APP_OPEN_SIGNALS.any { lower.contains(it) })                          score(scores, Intent.APP_LAUNCH,         4)
        if (PHONE_SIGNALS.any   { lower.contains(it) })                          score(scores, Intent.EXACT_FACT,         3)
        if (CALENDAR_SIGNALS.any{ lower.contains(it) })                          score(scores, Intent.CALENDAR_QUERY,     3)
        if (PROJECT_SIGNALS.any { lower.contains(it) })                          score(scores, Intent.PROJECT_STATUS,     2)
        if (HABIT_SIGNALS.any   { lower.contains(it) })                          score(scores, Intent.HABIT_INFERENCE,    2)
        if (TEMPORAL_SIGNALS.any{ lower.contains(it) })                          score(scores, Intent.TEMPORAL_QUERY,     2)
        if (RELATION_SIGNALS.any{ lower.contains(it) } && entities.isNotEmpty()) score(scores, Intent.RELATIONSHIP_QUERY, 2)
        if (MULTIHOP_SIGNALS.any{ lower.contains(it) } && entities.isNotEmpty()) score(scores, Intent.MULTI_HOP_GRAPH,    2)
        if (entities.isNotEmpty() && PROFILE_TRIGGERS.any { lower.contains(it) })score(scores, Intent.ENTITY_PROFILE,    2)
        if (SEMANTIC_TRIGGERS.any{ lower.contains(it) })                         score(scores, Intent.SEMANTIC_QUERY,     1)

        val intent   = scores.maxByOrNull { it.value }?.key ?: Intent.GENERAL
        val temporal = if (intent == Intent.TEMPORAL_QUERY || intent == Intent.CALENDAR_QUERY
                        || TEMPORAL_SIGNALS.any { lower.contains(it) })
                           parseTemporalFilter(lower) else null
        val capHint  = if (intent == Intent.APP_LAUNCH) inferCapability(lower) else null

        return QueryPlan(
            intent              = intent,
            entityNames         = entities,
            keywords            = keywords,
            temporalFilter      = temporal,
            paths               = pathsFor(intent, entities.isNotEmpty(), temporal != null),
            requiresLlm         = intent in LLM_INTENTS,
            appCapabilityHint   = capHint
        )
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun score(m: MutableMap<Intent, Int>, k: Intent, v: Int) { m[k] = (m[k] ?: 0) + v }

    private fun pathsFor(intent: Intent, hasEntities: Boolean, hasTemporal: Boolean): List<RetrievalPath> = when (intent) {
        Intent.APP_LAUNCH         -> listOf(RetrievalPath.KV_EXACT)
        Intent.EXACT_FACT         -> listOf(RetrievalPath.CONTACT_LOOKUP, RetrievalPath.KV_EXACT, RetrievalPath.FTS_BM25)
        Intent.ENTITY_PROFILE     -> listOf(RetrievalPath.GRAPH_TRAVERSAL, RetrievalPath.GRAPH_MULTIHOP, RetrievalPath.KV_EXACT, RetrievalPath.FTS_BM25)
        Intent.CONTACT_LOOKUP     -> listOf(RetrievalPath.CONTACT_LOOKUP, RetrievalPath.KV_EXACT)
        Intent.CALENDAR_QUERY     -> listOfNotNull(RetrievalPath.CALENDAR, if (hasTemporal) RetrievalPath.TEMPORAL_FILTER else null)
        Intent.PROJECT_STATUS     -> listOf(RetrievalPath.OBJECT_STORE, RetrievalPath.FTS_BM25, RetrievalPath.GRAPH_TRAVERSAL)
        Intent.RELATIONSHIP_QUERY -> listOf(RetrievalPath.GRAPH_TRAVERSAL, RetrievalPath.GRAPH_MULTIHOP, RetrievalPath.FTS_BM25)
        Intent.TEMPORAL_QUERY     -> listOfNotNull(RetrievalPath.TEMPORAL_FILTER, RetrievalPath.FTS_BM25, if (hasEntities) RetrievalPath.GRAPH_TRAVERSAL else null)
        Intent.SEMANTIC_QUERY     -> listOf(RetrievalPath.VECTOR_SEMANTIC, RetrievalPath.FTS_BM25, RetrievalPath.GRAPH_TRAVERSAL)
        Intent.HABIT_INFERENCE    -> listOf(RetrievalPath.TEMPORAL_FILTER, RetrievalPath.VECTOR_SEMANTIC, RetrievalPath.FTS_BM25)
        Intent.MULTI_HOP_GRAPH    -> listOf(RetrievalPath.GRAPH_MULTIHOP, RetrievalPath.GRAPH_TRAVERSAL, RetrievalPath.OBJECT_STORE)
        Intent.GENERAL            -> listOf(RetrievalPath.KV_EXACT, RetrievalPath.FTS_BM25, RetrievalPath.GRAPH_TRAVERSAL, RetrievalPath.VECTOR_SEMANTIC)
    }

    private fun extractEntities(query: String): List<String> =
        Regex("\\b([A-Z][a-z]{1,}(?:\\s[A-Z][a-z]{1,})?)\\b")
            .findAll(query)
            .map { it.value }
            .filter { it !in COMMON_CAPS }
            .distinct()
            .toList()

    private fun tokenize(lower: String): List<String> =
        lower.replace(Regex("[^a-z0-9\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && it !in STOP_WORDS }

    private fun parseTemporalFilter(lower: String): TemporalFilter? {
        val now = currentTimeMillis(); val DAY = 86_400_000L
        return when {
            lower.contains("yesterday")  -> TemporalFilter(now - 2 * DAY, now - DAY)
            lower.contains("today")      -> TemporalFilter(now - DAY,     now)
            lower.contains("last week")  -> TemporalFilter(now - 7 * DAY, now)
            lower.contains("last month") -> TemporalFilter(now - 30 * DAY,now)
            lower.contains("this week")  -> TemporalFilter(now - 7 * DAY, now)
            lower.contains("tomorrow")   -> TemporalFilter(now, now + 2 * DAY)
            else -> {
                val m = Regex("(\\d+)\\s+days?\\s+ago").find(lower)
                m?.groupValues?.get(1)?.toLongOrNull()?.let { days ->
                    TemporalFilter(now - days * DAY, now)
                }
            }
        }
    }

    private val APP_OPEN_SIGNALS  = setOf("open ", "launch ", "start ", "run ", "go to ")
    private val PHONE_SIGNALS     = setOf("phone", "number", "contact", "email", "address", "birthday", "dob")
    private val CALENDAR_SIGNALS  = setOf("meeting", "event", "appointment", "schedule", "calendar", "today", "tomorrow", "week", "remind")
    private val RELATION_SIGNALS  = setOf("work with", "know", "friend", "colleague", "team", "relationship", "connected", "associate")
    private val TEMPORAL_SIGNALS  = setOf("last week", "yesterday", "ago", "before", "after", "recent", "earlier", "previously", "when did")
    private val PROJECT_SIGNALS   = setOf("project", "task", "status", "progress", "milestone", "deadline", "deliverable")
    private val HABIT_SIGNALS     = setOf("usually", "habit", "always", "often", "routine", "typically", "every day", "when do i", "normally")
    private val MULTIHOP_SIGNALS  = setOf("what project", "who does", "connected to", "associated with", "involved in")
    private val PROFILE_TRIGGERS  = setOf("about", "tell me", "who is", "profile", "info on", "details")
    private val SEMANTIC_TRIGGERS = setOf("general", "overall", "concern", "feeling", "think about", "impression")
    private val LLM_INTENTS       = setOf(Intent.SEMANTIC_QUERY, Intent.GENERAL, Intent.HABIT_INFERENCE)

    private fun inferCapability(lower: String): com.newax.aegis.engine.apps.AppCapability {
        return when {
            lower.contains("send") || lower.contains("message") -> com.newax.aegis.engine.apps.AppCapability.SEND_TEXT
            lower.contains("call")  -> com.newax.aegis.engine.apps.AppCapability.CALL
            lower.contains("share") -> com.newax.aegis.engine.apps.AppCapability.SHARE_MEDIA
            lower.contains("play")  -> com.newax.aegis.engine.apps.AppCapability.PLAY_MEDIA
            lower.contains("navig") || lower.contains("directions") -> com.newax.aegis.engine.apps.AppCapability.NAVIGATE
            lower.contains("search") -> com.newax.aegis.engine.apps.AppCapability.SEARCH
            else                     -> com.newax.aegis.engine.apps.AppCapability.OPEN_APP
        }
    }

    private val STOP_WORDS = setOf("the", "and", "for", "are", "was", "with", "that", "this", "from", "have", "not", "what", "who", "how", "when", "where")
    private val COMMON_CAPS = setOf(
        "I", "What", "Who", "When", "Where", "How", "Tell", "Show", "Find", "Get",
        "Is", "Are", "Was", "The", "A", "An", "In", "On", "At", "To", "For", "Of",
        "And", "Or", "But", "With", "From", "By", "About", "Can", "Does", "Do", "Did"
    )
}
