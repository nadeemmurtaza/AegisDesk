package com.newax.aegis.desktop.planner

import java.util.concurrent.ConcurrentHashMap

/**
 * A skill the planner can put in a plan. Skills declare what platform surface
 * they need via [requiredCapabilities] — free-form names such as "OPEN_APP" or
 * "SEND_TEXT" — and the planner resolves those through the capability contract
 * ([com.newax.aegis.platform.CapabilityResolver]) against the process registry,
 * never by hand-written string matching (ARCHITECTURE.md: typed capability
 * references, not ad-hoc names).
 *
 * Desktop mirror of Android's `engine/intelligence/SkillRegistry` — same skill
 * ids, names and capability requirements, so plans read identically on both
 * bodies. Leaner than the Android registry: no handlers/stats yet (skill
 * *invocation* is the execution slice; this slice is registry + resolution).
 */
data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String,
    val requiredCapabilities: List<String> = emptyList(),
    val requiredPermissions: List<String> = emptyList(),
    val category: SkillCategory = SkillCategory.GENERAL,
)

enum class SkillCategory {
    GENERAL, COMMUNICATION, FILE, MEDIA, NAVIGATION, SEARCH,
    CALENDAR, SYSTEM, LEARNING, MEMORY, AUTOMATION, AI
}

object SkillRegistry {

    private val definitions = ConcurrentHashMap<String, SkillDefinition>()

    init {
        registerBuiltins()
    }

    fun register(definition: SkillDefinition) {
        definitions[definition.id] = definition
    }

    fun has(id: String): Boolean = definitions.containsKey(id)

    fun get(id: String): SkillDefinition? = definitions[id]

    fun findByCapability(capability: String): List<SkillDefinition> =
        definitions.values.filter { capability in it.requiredCapabilities }

    fun allSkills(): List<SkillDefinition> =
        definitions.values.sortedBy { it.name }

    private fun registerBuiltins() {
        register(
            SkillDefinition("find_app", "Find App", "Locate an installed app by name", category = SkillCategory.SYSTEM)
        )
        register(
            SkillDefinition(
                "launch_app", "Launch App", "Open an installed app",
                requiredCapabilities = listOf("OPEN_APP"), category = SkillCategory.SYSTEM
            )
        )
        register(
            SkillDefinition("find_contact", "Find Contact", "Look up a person by name or alias", category = SkillCategory.COMMUNICATION)
        )
        register(
            SkillDefinition("find_file", "Find File", "Search for files by name, type, or content", category = SkillCategory.FILE)
        )
        register(
            SkillDefinition("execute_search", "Execute Search", "Run a search across all indexed content", category = SkillCategory.SEARCH)
        )
        register(
            SkillDefinition("analyze_request", "Analyze Request", "Parse user intent and extract parameters", category = SkillCategory.AI)
        )
        register(
            SkillDefinition(
                "generate_summary", "Generate Summary", "Summarize content with LLM",
                requiredCapabilities = listOf("LLM"), category = SkillCategory.AI
            )
        )
        register(
            SkillDefinition("set_reminder", "Set Reminder", "Schedule a reminder", category = SkillCategory.CALENDAR)
        )
        register(
            SkillDefinition(
                "send_message", "Send Message", "Send a message via an app",
                requiredCapabilities = listOf("SEND_TEXT"), category = SkillCategory.COMMUNICATION
            )
        )
        register(
            SkillDefinition(
                "play_media", "Play Media", "Play audio or video content",
                requiredCapabilities = listOf("PLAY_MEDIA"), category = SkillCategory.MEDIA
            )
        )
    }
}
