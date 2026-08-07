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
            name = memory.getString(KEY_NAME) ?: "",
            language = memory.getString(KEY_LANGUAGE) ?: "en",
            timezone = memory.getString(KEY_TIMEZONE) ?: java.util.TimeZone.getDefault().id,
            communicationStyle = runCatching {
                CommunicationStyle.valueOf(memory.getString(KEY_COMMUNICATION_STYLE) ?: "BALANCED")
            }.getOrDefault(CommunicationStyle.BALANCED),
            responseLength = runCatching {
                ResponseLength.valueOf(memory.getString(KEY_RESPONSE_LENGTH) ?: "MEDIUM")
            }.getOrDefault(ResponseLength.MEDIUM),
            persona = memory.getString(KEY_PERSONA) ?: "helpful assistant",
            interests = memory.getStringSet(KEY_INTERESTS) ?: emptySet(),
            dislikes = memory.getStringSet(KEY_DISLIKES) ?: emptySet(),
            wakeWord = memory.getString(KEY_WAKE_WORD) ?: "Aegis"
        )
    }

    fun updateName(name: String) = memory.put(KEY_NAME, name)
    fun updateLanguage(lang: String) = memory.put(KEY_LANGUAGE, lang)
    fun updateTimezone(tz: String) = memory.put(KEY_TIMEZONE, tz)
    fun updateStyle(style: CommunicationStyle) = memory.put(KEY_COMMUNICATION_STYLE, style.name)
    fun updateResponseLength(length: ResponseLength) = memory.put(KEY_RESPONSE_LENGTH, length.name)
    fun updatePersona(persona: String) = memory.put(KEY_PERSONA, persona)
    fun updateWakeWord(word: String) = memory.put(KEY_WAKE_WORD, word)

    fun addInterest(interest: String) {
        val set = memory.getStringSet(KEY_INTERESTS)?.toMutableSet() ?: mutableSetOf()
        set.add(interest.lowercase().trim())
        memory.put(KEY_INTERESTS, set)
    }

    fun removeInterest(interest: String) {
        val set = memory.getStringSet(KEY_INTERESTS)?.toMutableSet() ?: return
        set.remove(interest.lowercase().trim())
        memory.put(KEY_INTERESTS, set)
    }

    fun addDislike(dislike: String) {
        val set = memory.getStringSet(KEY_DISLIKES)?.toMutableSet() ?: mutableSetOf()
        set.add(dislike.lowercase().trim())
        memory.put(KEY_DISLIKES, set)
    }

    fun isInterested(topic: String): Boolean {
        val interests = memory.getStringSet(KEY_INTERESTS) ?: return false
        return interests.any { topic.lowercase().contains(it) }
    }

    fun dislikes(topic: String): Boolean {
        val dislikes = memory.getStringSet(KEY_DISLIKES) ?: return false
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
