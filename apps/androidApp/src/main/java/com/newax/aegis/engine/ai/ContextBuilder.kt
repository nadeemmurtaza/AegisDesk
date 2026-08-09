package com.newax.aegis.engine.ai

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.manager.ProfileManager
import com.newax.aegis.engine.registry.PermissionRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContextWindow(
    val systemPrompt: String,
    val memorySection: String,
    val personSection: String,
    val fileSection: String,
    val calendarSection: String,
    val recentConversation: String,
    val estimatedTokens: Int
) {
    fun full(): String = buildString {
        append(systemPrompt)
        if (memorySection.isNotBlank()) { append("\n\n"); append(memorySection) }
        if (personSection.isNotBlank()) { append("\n\n"); append(personSection) }
        if (fileSection.isNotBlank()) { append("\n\n"); append(fileSection) }
        if (calendarSection.isNotBlank()) { append("\n\n"); append(calendarSection) }
        if (recentConversation.isNotBlank()) { append("\n\n"); append(recentConversation) }
    }
}

object ContextBuilder {

    private const val MAX_MEMORY_ENTRIES = 15
    private const val MAX_PERSON_FACTS = 10
    private const val MAX_FILES = 5
    private const val CHARS_PER_TOKEN = 4

    suspend fun build(
        query: String,
        db: AegisDatabase,
        recentConversation: List<Pair<String, String>> = emptyList(),
        includePeople: Boolean = true,
        includeFiles: Boolean = true,
        maxTokenBudget: Int = 2048
    ): ContextWindow = withContext(Dispatchers.IO) {
        val profileAdditions = runCatching { ProfileManager.getProfile() }
            .getOrNull()?.let { ProfileManager.systemPromptAdditions() } ?: ""

        val systemPrompt = buildSystemPrompt(profileAdditions)
        var remainingChars = maxTokenBudget * CHARS_PER_TOKEN - systemPrompt.length

        val memorySection = buildMemorySection(db, query, MAX_MEMORY_ENTRIES)
            .take(remainingChars / 3)
        remainingChars -= memorySection.length

        val personSection = if (includePeople)
            buildPersonSection(db, query, MAX_PERSON_FACTS).take(remainingChars / 3)
        else ""
        remainingChars -= personSection.length

        val fileSection = if (includeFiles && PermissionRegistry.isGranted(android.Manifest.permission.READ_CONTACTS))
            buildFileSection(db, query, MAX_FILES).take(remainingChars / 4)
        else ""
        remainingChars -= fileSection.length

        val calendarSection = buildCalendarSection(query).take(remainingChars / 4)
        remainingChars -= calendarSection.length

        val conversationSection = recentConversation.takeLast(6).joinToString("\n") { (role, text) ->
            "${role.uppercase()}: $text"
        }.take(remainingChars)

        val full = systemPrompt + memorySection + personSection + fileSection + calendarSection + conversationSection
        ContextWindow(
            systemPrompt = systemPrompt,
            memorySection = memorySection,
            personSection = personSection,
            fileSection = fileSection,
            calendarSection = calendarSection,
            recentConversation = conversationSection,
            estimatedTokens = full.length / CHARS_PER_TOKEN
        )
    }

    private fun buildSystemPrompt(profileAdditions: String): String = buildString {
        append("You are Aegis, an offline AI assistant. You have access to the user's private data. ")
        append("Be helpful, concise, and accurate. When you lack information, say so. ")
        if (profileAdditions.isNotBlank()) append(profileAdditions)
    }

    private suspend fun buildMemorySection(db: AegisDatabase, query: String, limit: Int): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val records = db.memoryRecordDao().current(limit)
                if (records.isEmpty()) return@withContext ""
                buildString {
                    append("MEMORY:\n")
                    records.forEach { append("• ${it.content}\n") }
                }
            }.getOrDefault("")
        }

    private suspend fun buildPersonSection(db: AegisDatabase, query: String, limit: Int): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val lower = query.lowercase()
                val mentions = db.personDao().getTopPeople(10)
                    .filter { p -> lower.contains(p.name.lowercase()) }
                    .take(3)
                if (mentions.isEmpty()) return@withContext ""
                buildString {
                    append("PEOPLE:\n")
                    mentions.forEach { person ->
                        val facts = db.personFactDao().forPerson(person.id).take(limit / 3)
                        append("${person.name}:\n")
                        facts.forEach { append("  - ${it.fact}\n") }
                    }
                }
            }.getOrDefault("")
        }

    private suspend fun buildFileSection(db: AegisDatabase, query: String, limit: Int): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val files = db.fileDao().recentUniqueFiles(limit)
                if (files.isEmpty()) return@withContext ""
                buildString {
                    append("RECENT FILES:\n")
                    files.forEach { f -> append("• ${f.filename} (${f.mimeType}, ${f.sizeBytes / 1024}KB)\n") }
                }
            }.getOrDefault("")
        }

    private fun buildCalendarSection(query: String): String {
        val lower = query.lowercase()
        if (!lower.contains("meet") && !lower.contains("schedule") && !lower.contains("calendar") &&
            !lower.contains("event") && !lower.contains("appointment")) return ""
        return ""
    }
}
