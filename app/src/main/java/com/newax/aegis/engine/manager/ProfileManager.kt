package com.newax.aegis.engine.manager

import android.content.Context
import com.newax.aegis.memory.EncryptedMemory

object ProfileManager {

    private const val KEY_NAME = "profile_name"
    private const val KEY_LANGUAGE = "profile_language"
    private const val KEY_TIMEZONE = "profile_timezone"
    private const val KEY_COMMUNICATION_STYLE = "profile_comm_style"
    private const val KEY_RESPONSE_LENGTH = "profile_response_length"
    private const val KEY_PERSONA = "profile_persona"
    private const val KEY_INTERESTS = "profile_interests_set"
    private const val KEY_DISLIKES = "profile_dislikes_set"
    private const val KEY_WAKE_WORD = "profile_wake_word"

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
            interests = memory.getCategory(KEY_INTERESTS).toSet(),
            dislikes = memory.getCategory(KEY_DISLIKES).toSet(),
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

    fun addInterest(interest: String) {
        val set = memory.getCategory(KEY_INTERESTS).toMutableSet()
        set.add(interest.lowercase().trim())
        memory.setCategory(KEY_INTERESTS, set.toList())
    }

    fun removeInterest(interest: String) {
        val set = memory.getCategory(KEY_INTERESTS).toMutableSet()
        if (!set.remove(interest.lowercase().trim())) return
        memory.setCategory(KEY_INTERESTS, set.toList())
    }

    fun addDislike(dislike: String) {
        val set = memory.getCategory(KEY_DISLIKES).toMutableSet()
        set.add(dislike.lowercase().trim())
        memory.setCategory(KEY_DISLIKES, set.toList())
    }

    fun isInterested(topic: String): Boolean {
        val interests = memory.getCategory(KEY_INTERESTS).takeIf { it.isNotEmpty() } ?: return false
        return interests.any { topic.lowercase().contains(it) }
    }

    fun dislikes(topic: String): Boolean {
        val dislikes = memory.getCategory(KEY_DISLIKES).takeIf { it.isNotEmpty() } ?: return false
        return dislikes.any { topic.lowercase().contains(it) }
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
