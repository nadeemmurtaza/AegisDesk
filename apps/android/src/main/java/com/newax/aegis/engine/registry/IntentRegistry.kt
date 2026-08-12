package com.newax.aegis.engine.registry

import java.util.concurrent.ConcurrentHashMap

data class IntentDefinition(
    val id: String,
    val name: String,
    val patterns: List<String>,
    val actionId: String,
    val requiredEntities: List<String> = emptyList(),
    val optionalEntities: List<String> = emptyList(),
    val confidence: Float = 0.8f,
    val examples: List<String> = emptyList()
)

data class IntentMatch(
    val intent: IntentDefinition,
    val confidence: Float,
    val extractedEntities: Map<String, String>
)

object IntentRegistry {

    private val intents = ConcurrentHashMap<String, IntentDefinition>()

    init {
        registerBuiltins()
    }

    fun register(intent: IntentDefinition) {
        intents[intent.id] = intent
    }

    fun unregister(id: String) = intents.remove(id)

    fun get(id: String): IntentDefinition? = intents[id]

    fun all(): List<IntentDefinition> = intents.values.toList()

    fun classify(query: String): List<IntentMatch> {
        val lower = query.lowercase().trim()
        return intents.values
            .mapNotNull { intent ->
                val matchedPattern = intent.patterns.firstOrNull { pattern ->
                    lower.contains(pattern.lowercase())
                }
                if (matchedPattern != null) {
                    val boost = matchedPattern.length.toFloat() / lower.length
                    IntentMatch(
                        intent = intent,
                        confidence = (intent.confidence * 0.8f + boost * 0.2f).coerceAtMost(1f),
                        extractedEntities = extractEntities(lower, intent.requiredEntities + intent.optionalEntities)
                    )
                } else null
            }
            .sortedByDescending { it.confidence }
    }

    fun topIntent(query: String): IntentMatch? = classify(query).firstOrNull()

    private fun extractEntities(text: String, entityTypes: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if ("person" in entityTypes) {
            val words = text.split(" ")
            val capitalized = words.filter { it.isNotEmpty() && it[0].isUpperCase() }
            if (capitalized.isNotEmpty()) result["person"] = capitalized.joinToString(" ")
        }
        if ("app" in entityTypes) {
            val known = listOf("whatsapp", "instagram", "twitter", "facebook", "gmail", "youtube", "spotify", "telegram")
            val found = known.firstOrNull { text.contains(it) }
            if (found != null) result["app"] = found
        }
        if ("file_type" in entityTypes) {
            val types = listOf("pdf", "image", "video", "audio", "document", "photo")
            val found = types.firstOrNull { text.contains(it) }
            if (found != null) result["file_type"] = found
        }
        return result
    }

    private fun registerBuiltins() {
        listOf(
            IntentDefinition("send_message", "Send Message",
                patterns = listOf("send", "message", "text", "msg", "whatsapp", "telegram"),
                actionId = "action_send_message",
                requiredEntities = listOf("person"),
                examples = listOf("Send a message to Ali", "WhatsApp John")),
            IntentDefinition("open_app", "Open App",
                patterns = listOf("open", "launch", "start", "run", "go to"),
                actionId = "action_open_app",
                requiredEntities = listOf("app"),
                examples = listOf("Open WhatsApp", "Launch Spotify")),
            IntentDefinition("find_file", "Find File",
                patterns = listOf("find", "search", "look for", "where is", "locate"),
                actionId = "action_find_file",
                optionalEntities = listOf("file_type", "person"),
                examples = listOf("Find the PDF I got yesterday", "Where is the photo of Ali")),
            IntentDefinition("call_person", "Call Person",
                patterns = listOf("call", "phone", "ring", "dial"),
                actionId = "action_call",
                requiredEntities = listOf("person"),
                examples = listOf("Call Mom", "Ring Ali")),
            IntentDefinition("set_reminder", "Set Reminder",
                patterns = listOf("remind", "reminder", "alert", "notify me"),
                actionId = "action_set_reminder",
                examples = listOf("Remind me at 5pm", "Set a reminder for tomorrow")),
            IntentDefinition("play_media", "Play Media",
                patterns = listOf("play", "listen", "watch", "music", "song", "video"),
                actionId = "action_play_media",
                examples = listOf("Play my workout playlist", "Watch a YouTube video")),
            IntentDefinition("search_info", "Search Information",
                patterns = listOf("what is", "who is", "tell me", "explain", "how to", "define"),
                actionId = "action_search_info",
                examples = listOf("What is machine learning", "Tell me about Ali")),
            IntentDefinition("navigate", "Navigate",
                patterns = listOf("navigate", "directions", "how to get to", "take me to", "route"),
                actionId = "action_navigate",
                examples = listOf("Navigate to home", "Directions to airport")),
            IntentDefinition("create_note", "Create Note",
                patterns = listOf("note", "write down", "record", "jot", "remember"),
                actionId = "action_create_note",
                examples = listOf("Note: meeting at 3pm", "Write down buy milk")),
            IntentDefinition("share_file", "Share File",
                patterns = listOf("share", "send file", "send photo", "forward"),
                actionId = "action_share_file",
                requiredEntities = listOf("person"),
                examples = listOf("Share the document with Ali"))
        ).forEach { register(it) }
    }
}
