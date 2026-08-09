package com.newax.aegis.engine.manager

import android.content.Context
import com.newax.aegis.memory.EncryptedMemory

object ProfileManager {

    // Scalars are stored as raw strings. They deliberately do NOT use EncryptedMemory's
    // "profile_" category prefix: getAllCategories() reads every profile_* key as a String
    // Set, so a scalar stored there would make it throw ClassCastException.
    private const val KEY_NAME = "userprofile_name"
    private const val KEY_LANGUAGE = "userprofile_language"
    private const val KEY_TIMEZONE = "userprofile_timezone"
    private const val KEY_COMMUNICATION_STYLE = "userprofile_comm_style"
    private const val KEY_RESPONSE_LENGTH = "userprofile_response_length"
    private const val KEY_PERSONA = "userprofile_persona"
    private const val KEY_WAKE_WORD = "userprofile_wake_word"

    // Multi-valued fields go through the category API, which is String-Set backed.
    private const val CATEGORY_INTERESTS = "interests"
    private const val CATEGORY_DISLIKES = "dislikes"

    data class UserProfile(
        val name: String = "",
        val language: String = "en",
        val timezone: String = "",
        val communicationStyle: CommunicationStyle = CommunicationStyle.BALANCED,
        val responseLength: ResponseLength = ResponseLength.MEDIUM,
        val persona: String = "helpful assistant",
        val interests: Set<String> = emptySet(),
        val dislikes: Set<String> = emptySet(),
        val wakeWord: String = "Aegis"
    )

    enum class CommunicationStyle { FORMAL, CASUAL, BALANCED, TECHNICAL }
    enum class ResponseLength { SHORT, MEDIUM, LONG, ADAPTIVE }

    private lateinit var memory: EncryptedMemory

    fun init(memory: EncryptedMemory) {
        ProfileManager.memory = memory
    }

    fun getProfile(): UserProfile {
        return UserProfile(
            name = memory.getRaw(KEY_NAME) ?: "",
            language = memory.getRaw(KEY_LANGUAGE) ?: "en",
            timezone = memory.getRaw(KEY_TIMEZONE) ?: java.util.TimeZone.getDefault().id,
            communicationStyle = runCatching {
                CommunicationStyle.valueOf(memory.getRaw(KEY_COMMUNICATION_STYLE) ?: "BALANCED")
            }.getOrDefault(CommunicationStyle.BALANCED),
            responseLength = runCatching {
                ResponseLength.valueOf(memory.getRaw(KEY_RESPONSE_LENGTH) ?: "MEDIUM")
            }.getOrDefault(ResponseLength.MEDIUM),
            persona = memory.getRaw(KEY_PERSONA) ?: "helpful assistant",
            interests = memory.getCategory(CATEGORY_INTERESTS).toSet(),
            dislikes = memory.getCategory(CATEGORY_DISLIKES).toSet(),
            wakeWord = memory.getRaw(KEY_WAKE_WORD) ?: "Aegis"
        )
    }

    fun updateName(name: String) = memory.storeRaw(KEY_NAME, name)
    fun updateLanguage(lang: String) = memory.storeRaw(KEY_LANGUAGE, lang)
    fun updateTimezone(tz: String) = memory.storeRaw(KEY_TIMEZONE, tz)
    fun updateStyle(style: CommunicationStyle) = memory.storeRaw(KEY_COMMUNICATION_STYLE, style.name)
    fun updateResponseLength(length: ResponseLength) = memory.storeRaw(KEY_RESPONSE_LENGTH, length.name)
    fun updatePersona(persona: String) = memory.storeRaw(KEY_PERSONA, persona)
    fun updateWakeWord(word: String) = memory.storeRaw(KEY_WAKE_WORD, word)

    fun addInterest(interest: String) = memory.remember(CATEGORY_INTERESTS, interest.lowercase().trim())

    fun removeInterest(interest: String) = memory.forget(CATEGORY_INTERESTS, interest.lowercase().trim())

    fun addDislike(dislike: String) = memory.remember(CATEGORY_DISLIKES, dislike.lowercase().trim())

    fun removeDislike(dislike: String) = memory.forget(CATEGORY_DISLIKES, dislike.lowercase().trim())

    fun isInterested(topic: String): Boolean {
        val needle = topic.lowercase()
        return memory.getCategory(CATEGORY_INTERESTS).any { needle.contains(it) }
    }

    fun dislikes(topic: String): Boolean {
        val needle = topic.lowercase()
        return memory.getCategory(CATEGORY_DISLIKES).any { needle.contains(it) }
    }

    fun systemPromptAdditions(): String {
        val profile = getProfile()
        return buildString {
            if (profile.name.isNotBlank()) append("User's name: ${profile.name}. ")
            if (profile.language != "en") append("Respond in ${profile.language}. ")
            when (profile.communicationStyle) {
                CommunicationStyle.FORMAL -> append("Use formal language. ")
                CommunicationStyle.CASUAL -> append("Use casual, friendly language. ")
                CommunicationStyle.TECHNICAL -> append("Use technical and precise language. ")
                CommunicationStyle.BALANCED -> Unit
            }
            when (profile.responseLength) {
                ResponseLength.SHORT -> append("Be concise. ")
                ResponseLength.LONG -> append("Provide detailed responses. ")
                ResponseLength.ADAPTIVE -> append("Adapt response length to the question. ")
                ResponseLength.MEDIUM -> Unit
            }
            if (profile.interests.isNotEmpty()) append("User is interested in: ${profile.interests.take(5).joinToString(", ")}. ")
        }
    }
}
