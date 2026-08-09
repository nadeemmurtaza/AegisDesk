package com.newax.aegis.engine.intelligence

import com.newax.aegis.engine.bus.AegisEvent
import com.newax.aegis.engine.bus.AegisEventBus
import java.util.concurrent.ConcurrentHashMap

data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String,
    val requiredCapabilities: List<String> = emptyList(),
    val requiredPermissions: List<String> = emptyList(),
    val inputSchema: Map<String, String> = emptyMap(),
    val outputSchema: Map<String, String> = emptyMap(),
    val category: SkillCategory = SkillCategory.GENERAL,
    val averageMs: Long = 0L,
    val successRate: Float = 1f,
    val invocationCount: Long = 0L,
    val tags: List<String> = emptyList()
)

enum class SkillCategory {
    GENERAL, COMMUNICATION, FILE, MEDIA, NAVIGATION, SEARCH,
    CALENDAR, SYSTEM, LEARNING, MEMORY, AUTOMATION, AI
}

typealias SkillHandler = suspend (inputs: Map<String, Any>) -> Map<String, Any>

object SkillRegistry {

    private val definitions = ConcurrentHashMap<String, SkillDefinition>()
    private val handlers = ConcurrentHashMap<String, SkillHandler>()
    private val stats = ConcurrentHashMap<String, SkillStats>()

    private data class SkillStats(
        var invocations: Long = 0,
        var successes: Long = 0,
        var totalMs: Long = 0
    ) {
        val successRate: Float get() = if (invocations == 0L) 1f else successes.toFloat() / invocations
        val averageMs: Long get() = if (invocations == 0L) 0L else totalMs / invocations
    }

    init {
        registerBuiltins()
    }

    fun register(definition: SkillDefinition, handler: SkillHandler) {
        definitions[definition.id] = definition
        handlers[definition.id] = handler
        stats[definition.id] = SkillStats()
    }

    fun has(id: String): Boolean = definitions.containsKey(id)

    fun get(id: String): SkillDefinition? = definitions[id]

    suspend fun invoke(id: String, inputs: Map<String, Any> = emptyMap()): Result<Map<String, Any>> {
        val handler = handlers[id] ?: return Result.failure(IllegalArgumentException("Skill not found: $id"))
        val s = stats.getOrPut(id) { SkillStats() }
        val startMs = System.currentTimeMillis()
        s.invocations++
        return try {
            val result = handler(inputs)
            val durationMs = System.currentTimeMillis() - startMs
            s.successes++
            s.totalMs += durationMs
            AegisEventBus.emit(AegisEvent.SkillInvoked(id, true, durationMs))
            Result.success(result)
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startMs
            s.totalMs += durationMs
            AegisEventBus.emit(AegisEvent.SkillInvoked(id, false, durationMs))
            Result.failure(e)
        }
    }

    fun findByCapability(capability: String): List<SkillDefinition> =
        definitions.values.filter { capability in it.requiredCapabilities }

    fun findByCategory(category: SkillCategory): List<SkillDefinition> =
        definitions.values.filter { it.category == category }

    fun findByTag(tag: String): List<SkillDefinition> =
        definitions.values.filter { tag in it.tags }

    fun search(query: String): List<SkillDefinition> {
        val lower = query.lowercase()
        return definitions.values.filter {
            it.name.lowercase().contains(lower) ||
            it.description.lowercase().contains(lower) ||
            it.tags.any { t -> t.lowercase().contains(lower) }
        }.sortedByDescending { it.successRate }
    }

    fun statsFor(id: String): SkillDefinition? {
        val def = definitions[id] ?: return null
        val s = stats[id] ?: return def
        return def.copy(
            invocationCount = s.invocations,
            successRate = s.successRate,
            averageMs = s.averageMs
        )
    }

    fun allSkills(): List<SkillDefinition> = definitions.values
        .map { def ->
            val s = stats[def.id]
            if (s != null) def.copy(invocationCount = s.invocations, successRate = s.successRate, averageMs = s.averageMs)
            else def
        }
        .sortedBy { it.name }

    fun topSkills(n: Int = 10): List<SkillDefinition> = allSkills()
        .sortedByDescending { it.invocationCount }.take(n)

    private fun registerBuiltins() {
        register(
            SkillDefinition("find_app", "Find App", "Locate an installed app by name", category = SkillCategory.SYSTEM),
            handler = { inputs -> mapOf("packageName" to (inputs["query"] ?: "")) }
        )
        register(
            SkillDefinition("launch_app", "Launch App", "Open an installed app", requiredCapabilities = listOf("OPEN_APP"), category = SkillCategory.SYSTEM),
            handler = { inputs -> mapOf("launched" to true, "packageName" to (inputs["packageName"] ?: "")) }
        )
        register(
            SkillDefinition("find_contact", "Find Contact", "Look up a person by name or alias", category = SkillCategory.COMMUNICATION),
            handler = { inputs -> mapOf("name" to (inputs["name"] ?: ""), "found" to false) }
        )
        register(
            SkillDefinition("find_file", "Find File", "Search for files by name, type, or content", category = SkillCategory.FILE),
            handler = { inputs -> mapOf("query" to (inputs["query"] ?: ""), "results" to emptyList<String>()) }
        )
        register(
            SkillDefinition("execute_search", "Execute Search", "Run a search across all indexed content", category = SkillCategory.SEARCH),
            handler = { inputs -> mapOf("query" to (inputs["query"] ?: ""), "results" to emptyList<String>()) }
        )
        register(
            SkillDefinition("analyze_request", "Analyze Request", "Parse user intent and extract parameters", category = SkillCategory.AI),
            handler = { inputs -> mapOf("intent" to "general", "entities" to emptyList<String>()) }
        )
        register(
            SkillDefinition("generate_summary", "Generate Summary", "Summarize content with LLM", requiredCapabilities = listOf("LLM"), category = SkillCategory.AI),
            handler = { inputs -> mapOf("summary" to "") }
        )
        register(
            SkillDefinition("set_reminder", "Set Reminder", "Schedule a reminder", requiredPermissions = listOf("android.permission.WRITE_CALENDAR"), category = SkillCategory.CALENDAR),
            handler = { inputs -> mapOf("created" to false) }
        )
        register(
            SkillDefinition("send_message", "Send Message", "Send a message via an app", requiredCapabilities = listOf("SEND_TEXT"), category = SkillCategory.COMMUNICATION),
            handler = { inputs -> mapOf("sent" to false) }
        )
        register(
            SkillDefinition("play_media", "Play Media", "Play audio or video content", requiredCapabilities = listOf("PLAY_MEDIA"), category = SkillCategory.MEDIA),
            handler = { inputs -> mapOf("playing" to false) }
        )
    }
}
