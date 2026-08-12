package com.newax.aegis.engine

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.newax.aegis.memory.EncryptedMemory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads complete SMS + CommunicationLog history for a contact and builds a rich
 * PersonIntelligenceProfile covering personality traits, relationship type, tone,
 * writing style, common topics, and communication patterns.
 *
 * All profiles are encrypted and stored in EncryptedMemory.
 */
class PersonIntelligence(private val context: Context, private val memory: EncryptedMemory) {

    private val TAG = "NewaxPersonIntel"
    private val PROFILE_KEY_PREFIX = "person_intel_"

    // ── Data structures ──────────────────────────────────────────────────────────

    enum class RelationshipCategory {
        FAMILY, ROMANTIC_PARTNER, CLOSE_FRIEND, FRIEND, COLLEAGUE,
        PROFESSIONAL_SERVICE, ACQUAINTANCE, UNKNOWN
    }

    data class WritingStyle(
        val avgWordCount: Float,
        val avgMessageLength: Int,
        val usesEmoji: Boolean,
        val emojiFrequency: Float,       // emojis per 10 messages
        val usesAbbreviations: Boolean,
        val usesPunctuation: Boolean,
        val capsRatio: Float,            // fraction of letters in CAPS
        val commonOpenings: List<String>,
        val commonPhrases: List<String>
    )

    data class PersonIntelligenceProfile(
        val contactId: String,
        val displayName: String,
        // Relationship
        val relationship: RelationshipCategory,
        val intimacyScore: Float,        // 0.0 = stranger, 1.0 = very close
        val trustScore: Float,           // 0.0 = untrusted, 1.0 = fully trusted
        // Personality
        val personalityTraits: List<String>,  // ["warm", "professional", "direct", "verbose", …]
        val sentimentTowardMe: String,   // "positive", "neutral", "negative", "mixed"
        // Communication patterns
        val formalityScore: Float,       // 0.0 = very casual, 1.0 = very formal
        val urgencyAvg: Float,           // avg urgency score from ToneAnalyzer
        val dominantIntent: String,      // most common intent (REQUEST, INFORMATION, etc.)
        val communicationFrequency: String,  // "daily", "weekly", "monthly", "occasional"
        val avgResponseGapHours: Float,
        val initiatesConversation: Boolean,  // do they start most conversations
        // Language & writing
        val writingStyle: WritingStyle,
        val languagesDetected: List<String>,
        val topicKeywords: List<String>, // what this person talks about most
        // Stats
        val totalMessagesIn: Int,
        val totalMessagesOut: Int,
        val firstContactMs: Long,
        val lastContactMs: Long,
        val lastAnalyzedMs: Long,
        // Summary for AI injection
        val aiSummary: String
    )

    private data class RawMessage(
        val body: String,
        val timestampMs: Long,
        val isIncoming: Boolean
    )

    // ── SMS reading ──────────────────────────────────────────────────────────────

    private fun readSmsHistory(phoneNumber: String, limit: Int = 300): List<RawMessage> {
        val messages = mutableListOf<RawMessage>()
        val normalizedPhone = ContactNormalizer.normalizePhone(phoneNumber)

        // Query both inbox (type=1) and sent (type=2)
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )

        val allPhoneVariants = buildList {
            add(phoneNumber)
            add(normalizedPhone)
            // Also try short form (last 10 digits)
            val short = phoneNumber.filter { it.isDigit() }.takeLast(10)
            if (short.isNotEmpty()) add(short)
        }.distinct()

        for (variant in allPhoneVariants) {
            try {
                context.contentResolver.query(
                    uri, projection,
                    "${Telephony.Sms.ADDRESS} LIKE ?",
                    arrayOf("%$variant%"),
                    "${Telephony.Sms.DATE} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext() && messages.size < limit) {
                        val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: continue
                        val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
                        val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                        val isIncoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                        if (body.isNotBlank()) messages += RawMessage(body, date, isIncoming)
                    }
                }
                if (messages.isNotEmpty()) break  // found with this variant
            } catch (e: Exception) {
                Log.w(TAG, "SMS query failed for $variant: ${e.message}")
            }
        }

        return messages.sortedByDescending { it.timestampMs }
    }

    private fun readCommLogHistory(contactName: String): List<RawMessage> =
        CommunicationLog.getLogsForContact(contactName, limit = 150).map { entry ->
            RawMessage(
                body = entry.message,
                timestampMs = entry.timestamp,
                isIncoming = entry.direction == "IN"
            )
        }

    // ── Analysis ─────────────────────────────────────────────────────────────────

    fun buildProfile(
        contactId: String,
        displayName: String,
        phoneNumbers: List<String>,
        emails: List<String>
    ): PersonIntelligenceProfile {
        // Gather all messages from SMS + CommunicationLog
        val allMessages = mutableListOf<RawMessage>()
        for (phone in phoneNumbers) allMessages += readSmsHistory(phone)
        allMessages += readCommLogHistory(displayName)
        // Also check by email-derived name
        for (email in emails) allMessages += readCommLogHistory(email)

        val deduplicated = allMessages.distinctBy { it.timestampMs.toString() + it.body.take(20) }
            .sortedByDescending { it.timestampMs }
            .take(400)

        val incoming = deduplicated.filter { it.isIncoming }
        val outgoing = deduplicated.filter { !it.isIncoming }

        // Time spans
        val firstMs = deduplicated.minOfOrNull { it.timestampMs } ?: System.currentTimeMillis()
        val lastMs  = deduplicated.maxOfOrNull { it.timestampMs } ?: System.currentTimeMillis()

        // Run tone analysis on incoming messages (batched to reduce cost)
        val tones = analyzeTonesBatched(incoming)
        val myTones = analyzeTonesBatched(outgoing)

        // Writing style
        val incomingStyle  = computeWritingStyle(incoming)
        val languages      = detectLanguages(incoming + outgoing)
        val topicKeywords  = extractTopicKeywords(deduplicated)

        // Relationship
        val relationship = inferRelationship(displayName, incoming, outgoing, tones)
        val intimacy     = computeIntimacy(incoming, outgoing, relationship, tones)
        val trust        = computeTrust(tones, displayName)

        // Personality traits (from their messages = incoming)
        val traits = inferPersonalityTraits(incoming, tones)

        // Sentiment toward me
        val sentPos = tones.count { it.sentiment == ToneAnalyzer.Sentiment.POSITIVE }.toFloat()
        val sentNeg = tones.count { it.sentiment == ToneAnalyzer.Sentiment.NEGATIVE }.toFloat()
        val sentimentTowardMe = when {
            tones.isEmpty()                -> "unknown"
            sentPos > sentNeg * 2          -> "positive"
            sentNeg > sentPos * 2          -> "negative"
            sentPos > 0 && sentNeg > 0     -> "mixed"
            else                           -> "neutral"
        }

        // Communication patterns
        val formality = if (tones.isEmpty()) 0.5f else
            tones.count { it.formality == ToneAnalyzer.Formality.FORMAL }.toFloat() / tones.size
        val urgency = if (tones.isEmpty()) 0f else tones.map { it.urgency }.average().toFloat()
        val freq = computeFrequency(deduplicated, firstMs, lastMs)
        val initiates = incoming.size > outgoing.size * 1.5f

        // Dominant intent
        val dominantIntent = if (tones.isEmpty()) "INFORMATION" else
            tones.groupBy { it.intent.name }.maxByOrNull { it.value.size }?.key ?: "INFORMATION"

        // Avg response gap
        val avgGap = computeAvgResponseGap(deduplicated)

        val profile = PersonIntelligenceProfile(
            contactId = contactId,
            displayName = displayName,
            relationship = relationship,
            intimacyScore = intimacy,
            trustScore = trust,
            personalityTraits = traits,
            sentimentTowardMe = sentimentTowardMe,
            formalityScore = formality,
            urgencyAvg = urgency,
            dominantIntent = dominantIntent,
            communicationFrequency = freq,
            avgResponseGapHours = avgGap,
            initiatesConversation = initiates,
            writingStyle = incomingStyle,
            languagesDetected = languages,
            topicKeywords = topicKeywords,
            totalMessagesIn = incoming.size,
            totalMessagesOut = outgoing.size,
            firstContactMs = firstMs,
            lastContactMs = lastMs,
            lastAnalyzedMs = System.currentTimeMillis(),
            aiSummary = buildAiSummary(displayName, relationship, traits, sentimentTowardMe, formality, freq, topicKeywords)
        )

        saveProfile(profile)
        return profile
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private fun analyzeTonesBatched(messages: List<RawMessage>): List<ToneAnalyzer.ToneProfile> {
        if (messages.isEmpty()) return emptyList()
        // Analyze every 5th message for performance (and last 20 in full)
        val recent = messages.take(20)
        val sample = messages.drop(20).filterIndexed { i, _ -> i % 5 == 0 }.take(30)
        return (recent + sample).map { ToneAnalyzer.analyze(it.body) }
    }

    private fun computeWritingStyle(messages: List<RawMessage>): WritingStyle {
        if (messages.isEmpty()) return WritingStyle(0f, 0, false, 0f, false, true, 0f, emptyList(), emptyList())
        val bodies = messages.map { it.body }
        val wordCounts = bodies.map { it.split(Regex("\\s+")).size }
        val avgWords = wordCounts.average().toFloat()
        val avgLen = bodies.map { it.length }.average().toInt()

        val EMOJI_RE = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF]")
        val emojiTotal = bodies.sumOf { EMOJI_RE.findAll(it).count() }
        val usesEmoji = emojiTotal > 0
        val emojiFreq = emojiTotal.toFloat() / maxOf(1, messages.size) * 10

        val abbrevSignals = setOf("k ", "ok ", "brb", "lol", "omg", "btw", "idk", "tbh", "ngl", "nah", "yup", "smh")
        val usesAbbrev = bodies.any { b -> abbrevSignals.any { b.lowercase().contains(it) } }

        val punctuationCount = bodies.sumOf { b -> b.count { c -> c in ".!?" } }
        val usesPunct = punctuationCount > messages.size / 2

        val allLetters = bodies.joinToString("").filter { it.isLetter() }
        val capsRatio = if (allLetters.isEmpty()) 0f else
            allLetters.count { it.isUpperCase() }.toFloat() / allLetters.length

        // Common openings: first word of message
        val openings = bodies.map { it.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: "" }
            .filter { it.isNotBlank() }
            .groupBy { it }.entries
            .sortedByDescending { it.value.size }
            .take(3).map { it.key }

        // Common phrases: 3-grams
        val trigramFreq = mutableMapOf<String, Int>()
        for (body in bodies) {
            val words = body.lowercase().split(Regex("\\s+"))
            for (i in 0..words.size - 3) {
                val tg = "${words[i]} ${words[i+1]} ${words[i+2]}"
                trigramFreq[tg] = (trigramFreq[tg] ?: 0) + 1
            }
        }
        val commonPhrases = trigramFreq.entries.filter { it.value >= 2 }
            .sortedByDescending { it.value }.take(5).map { it.key }

        return WritingStyle(avgWords, avgLen, usesEmoji, emojiFreq, usesAbbrev, usesPunct, capsRatio, openings, commonPhrases)
    }

    private fun detectLanguages(messages: List<RawMessage>): List<String> {
        val langs = mutableSetOf<String>()
        val sample = messages.take(30)
        for (msg in sample) {
            when {
                msg.body.any { c -> c.code in 0x0600..0x06FF } -> langs += "Urdu/Arabic"
                msg.body.any { c -> c.code in 0x0900..0x097F } -> langs += "Hindi"
                else -> langs += "English"
            }
        }
        return langs.toList()
    }

    private fun extractTopicKeywords(messages: List<RawMessage>): List<String> {
        val allText = messages.joinToString(" ") { it.body.lowercase() }
        val stopWords = setOf("the","a","an","in","on","at","to","for","of","and","or","but","with","from","is","are","was","were","i","you","he","she","we","they","it","this","that","have","has","had","do","did","will","would","can","could","should","may","might","be","been","being","not","no","so","if","as","by","up","my","your","our","his","her","their","its","me","him","us","them","what","how","when","where","why","who","ok","okay","hi","hello","yes","no","yeah","lol","haha","thanks","thank","please","sorry","bhi","ap","aap","main","kya","hai","ho","kar","ke","ki","ka","se","ne","ko")
        val words = allText.split(Regex("\\W+")).filter { it.length > 3 && it !in stopWords }
        return words.groupBy { it }.entries
            .sortedByDescending { it.value.size }
            .take(15).map { it.key }
    }

    private fun inferRelationship(
        name: String, incoming: List<RawMessage>, outgoing: List<RawMessage>,
        tones: List<ToneAnalyzer.ToneProfile>
    ): RelationshipCategory {
        val combined = (incoming + outgoing).joinToString(" ") { it.body.lowercase() }

        val FAMILY_TERMS = setOf("bhai", "brother", "sister", "behen", "amma", "ammi", "mom", "mum", "mother", "dad", "father", "abbu", "abba", "papa", "beta", "beti", "son", "daughter", "uncle", "aunt", "chachu", "mamoo", "khala", "phupho", "nana", "nani", "dada", "dadi", "cousin", "bhabhi", "jija", "wife", "husband", "family")
        val ROMANTIC_TERMS = setOf("love", "jaan", "pyar", "darling", "sweetheart", "miss you", "i love you", "my life", "heart", "babe", "baby", "hun", "honey", "dear one", "tu meri")
        val PROFESSIONAL_TERMS = setOf("sir", "ma'am", "meeting", "office", "project", "deadline", "report", "client", "invoice", "business", "company", "department", "manager", "work")

        val familyScore    = FAMILY_TERMS.count { combined.contains(it) }
        val romanticScore  = ROMANTIC_TERMS.count { combined.contains(it) }
        val professionalScore = PROFESSIONAL_TERMS.count { combined.contains(it) }

        val avgFormality = if (tones.isEmpty()) 0.5f else
            tones.count { it.formality == ToneAnalyzer.Formality.FORMAL }.toFloat() / tones.size
        val totalMsgs = incoming.size + outgoing.size

        return when {
            romanticScore >= 2                              -> RelationshipCategory.ROMANTIC_PARTNER
            familyScore >= 2                               -> RelationshipCategory.FAMILY
            professionalScore >= 3 && avgFormality > 0.5f -> RelationshipCategory.PROFESSIONAL_SERVICE
            professionalScore >= 2                         -> RelationshipCategory.COLLEAGUE
            totalMsgs >= 100                               -> RelationshipCategory.CLOSE_FRIEND
            totalMsgs >= 20                                -> RelationshipCategory.FRIEND
            totalMsgs >= 5                                 -> RelationshipCategory.ACQUAINTANCE
            else                                           -> RelationshipCategory.UNKNOWN
        }
    }

    private fun computeIntimacy(
        incoming: List<RawMessage>, outgoing: List<RawMessage>,
        rel: RelationshipCategory, tones: List<ToneAnalyzer.ToneProfile>
    ): Float {
        var score = when (rel) {
            RelationshipCategory.ROMANTIC_PARTNER  -> 0.9f
            RelationshipCategory.FAMILY            -> 0.8f
            RelationshipCategory.CLOSE_FRIEND      -> 0.7f
            RelationshipCategory.FRIEND            -> 0.5f
            RelationshipCategory.COLLEAGUE         -> 0.3f
            RelationshipCategory.PROFESSIONAL_SERVICE -> 0.2f
            else                                   -> 0.1f
        }
        val totalMsgs = (incoming.size + outgoing.size).coerceAtMost(400)
        score += (totalMsgs / 400f) * 0.15f
        val positiveRatio = if (tones.isEmpty()) 0f else
            tones.count { it.sentiment == ToneAnalyzer.Sentiment.POSITIVE }.toFloat() / tones.size
        score += positiveRatio * 0.05f
        return score.coerceIn(0f, 1f)
    }

    private fun computeTrust(tones: List<ToneAnalyzer.ToneProfile>, name: String): Float {
        if (tones.isEmpty()) return 0.5f
        val phishingMax = tones.maxOf { it.phishingRisk }
        val threatMax   = tones.maxOf { it.threat }
        return when {
            phishingMax > 0.6f || threatMax > 0.6f -> 0.1f
            phishingMax > 0.3f || threatMax > 0.3f -> 0.4f
            else -> 0.75f
        }
    }

    private fun inferPersonalityTraits(
        incoming: List<RawMessage>,
        tones: List<ToneAnalyzer.ToneProfile>
    ): List<String> {
        val traits = mutableListOf<String>()
        if (incoming.isEmpty()) return listOf("insufficient data")

        val bodies = incoming.map { it.body }
        val avgLen = bodies.map { it.length }.average()
        val combined = bodies.joinToString(" ").lowercase()

        if (avgLen > 150) traits += "verbose" else if (avgLen < 30) traits += "terse/direct"
        if (tones.any { it.formality == ToneAnalyzer.Formality.FORMAL }) traits += "formal"
        if (tones.any { it.formality == ToneAnalyzer.Formality.INFORMAL }) traits += "casual"

        val positiveRatio = if (tones.isEmpty()) 0f else
            tones.count { it.sentiment == ToneAnalyzer.Sentiment.POSITIVE }.toFloat() / tones.size
        if (positiveRatio > 0.6f) traits += "warm/positive"
        else if (positiveRatio < 0.2f) traits += "reserved"

        val urgencyAvg = if (tones.isEmpty()) 0f else tones.map { it.urgency }.average().toFloat()
        if (urgencyAvg > 0.5f) traits += "urgent/assertive"

        if (combined.contains("sorry") || combined.contains("please forgive")) traits += "apologetic"
        if (combined.contains("thank") || combined.contains("appreciate")) traits += "grateful"
        if (combined.contains("haha") || combined.contains("lol") || combined.contains("😂")) traits += "humorous"

        val EMOJI_RE = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]")
        if (EMOJI_RE.containsMatchIn(combined)) traits += "expressive (emoji)"

        val questionCount = bodies.count { it.contains('?') }
        if (questionCount > incoming.size * 0.3f) traits += "inquisitive"

        if (tones.any { it.intent == ToneAnalyzer.Intent.REQUEST } &&
            tones.count { it.intent == ToneAnalyzer.Intent.REQUEST } > tones.size * 0.3f)
            traits += "tends to make requests"

        return traits.distinct().take(8)
    }

    private fun computeFrequency(messages: List<RawMessage>, firstMs: Long, lastMs: Long): String {
        if (messages.isEmpty()) return "no data"
        val days = maxOf(1L, (lastMs - firstMs) / (1000L * 60 * 60 * 24))
        val perDay = messages.size.toFloat() / days
        return when {
            perDay >= 5f   -> "daily (frequent)"
            perDay >= 1f   -> "daily"
            perDay >= 0.14f -> "weekly"
            perDay >= 0.03f -> "monthly"
            else            -> "occasional"
        }
    }

    private fun computeAvgResponseGap(messages: List<RawMessage>): Float {
        if (messages.size < 4) return 0f
        val sorted = messages.sortedBy { it.timestampMs }
        var totalGap = 0L; var count = 0
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]; val curr = sorted[i]
            if (prev.isIncoming != curr.isIncoming) {
                val gap = curr.timestampMs - prev.timestampMs
                if (gap < 72 * 60 * 60 * 1000L) { totalGap += gap; count++ }
            }
        }
        if (count == 0) return 0f
        return (totalGap.toFloat() / count) / (1000f * 60 * 60)  // hours
    }

    private fun buildAiSummary(
        name: String, rel: RelationshipCategory, traits: List<String>,
        sentiment: String, formality: Float, freq: String, topics: List<String>
    ): String = buildString {
        appendLine("$name is a ${rel.name.lowercase().replace('_', ' ')}.")
        if (traits.isNotEmpty()) appendLine("Personality: ${traits.joinToString(", ")}.")
        appendLine("Their messages toward me are generally $sentiment.")
        val formalLabel = when {
            formality > 0.7f -> "very formal"
            formality > 0.4f -> "moderately formal"
            else -> "casual/informal"
        }
        appendLine("Communication style: $formalLabel, frequency: $freq.")
        if (topics.isNotEmpty()) appendLine("Common topics: ${topics.take(5).joinToString(", ")}.")
    }.trim()

    // ── Storage ───────────────────────────────────────────────────────────────────

    fun saveProfile(profile: PersonIntelligenceProfile) {
        val json = serializeProfile(profile)
        memory.storeRaw("$PROFILE_KEY_PREFIX${profile.contactId}", json)
    }

    fun loadProfile(contactId: String): PersonIntelligenceProfile? {
        val json = memory.getRaw("$PROFILE_KEY_PREFIX$contactId") ?: return null
        return try { deserializeProfile(json) } catch (_: Exception) { null }
    }

    fun loadAllProfiles(): List<PersonIntelligenceProfile> {
        // EncryptedMemory doesn't expose all raw keys, so we load from KnowledgeGraph index
        // This is a known limitation — profiles are found by contactId lookup
        return emptyList()
    }

    private fun serializeProfile(p: PersonIntelligenceProfile): String = JSONObject().apply {
        put("contactId", p.contactId)
        put("displayName", p.displayName)
        put("relationship", p.relationship.name)
        put("intimacyScore", p.intimacyScore)
        put("trustScore", p.trustScore)
        put("personalityTraits", JSONArray(p.personalityTraits))
        put("sentimentTowardMe", p.sentimentTowardMe)
        put("formalityScore", p.formalityScore)
        put("urgencyAvg", p.urgencyAvg)
        put("dominantIntent", p.dominantIntent)
        put("communicationFrequency", p.communicationFrequency)
        put("avgResponseGapHours", p.avgResponseGapHours)
        put("initiatesConversation", p.initiatesConversation)
        put("languagesDetected", JSONArray(p.languagesDetected))
        put("topicKeywords", JSONArray(p.topicKeywords))
        put("totalMessagesIn", p.totalMessagesIn)
        put("totalMessagesOut", p.totalMessagesOut)
        put("firstContactMs", p.firstContactMs)
        put("lastContactMs", p.lastContactMs)
        put("lastAnalyzedMs", p.lastAnalyzedMs)
        put("aiSummary", p.aiSummary)
        // WritingStyle
        put("ws_avgWordCount", p.writingStyle.avgWordCount)
        put("ws_avgLen", p.writingStyle.avgMessageLength)
        put("ws_emoji", p.writingStyle.usesEmoji)
        put("ws_emojiFreq", p.writingStyle.emojiFrequency)
        put("ws_abbrev", p.writingStyle.usesAbbreviations)
        put("ws_punct", p.writingStyle.usesPunctuation)
        put("ws_caps", p.writingStyle.capsRatio)
        put("ws_openings", JSONArray(p.writingStyle.commonOpenings))
        put("ws_phrases", JSONArray(p.writingStyle.commonPhrases))
    }.toString()

    private fun deserializeProfile(json: String): PersonIntelligenceProfile {
        val o = JSONObject(json)
        fun jarr(key: String) = o.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
        val ws = WritingStyle(
            avgWordCount = o.optDouble("ws_avgWordCount", 0.0).toFloat(),
            avgMessageLength = o.optInt("ws_avgLen", 0),
            usesEmoji = o.optBoolean("ws_emoji", false),
            emojiFrequency = o.optDouble("ws_emojiFreq", 0.0).toFloat(),
            usesAbbreviations = o.optBoolean("ws_abbrev", false),
            usesPunctuation = o.optBoolean("ws_punct", true),
            capsRatio = o.optDouble("ws_caps", 0.0).toFloat(),
            commonOpenings = jarr("ws_openings"),
            commonPhrases = jarr("ws_phrases")
        )
        return PersonIntelligenceProfile(
            contactId = o.getString("contactId"),
            displayName = o.getString("displayName"),
            relationship = try { RelationshipCategory.valueOf(o.getString("relationship")) } catch (_: Exception) { RelationshipCategory.UNKNOWN },
            intimacyScore = o.optDouble("intimacyScore", 0.5).toFloat(),
            trustScore = o.optDouble("trustScore", 0.5).toFloat(),
            personalityTraits = jarr("personalityTraits"),
            sentimentTowardMe = o.optString("sentimentTowardMe", "neutral"),
            formalityScore = o.optDouble("formalityScore", 0.5).toFloat(),
            urgencyAvg = o.optDouble("urgencyAvg", 0.0).toFloat(),
            dominantIntent = o.optString("dominantIntent", "INFORMATION"),
            communicationFrequency = o.optString("communicationFrequency", "occasional"),
            avgResponseGapHours = o.optDouble("avgResponseGapHours", 0.0).toFloat(),
            initiatesConversation = o.optBoolean("initiatesConversation", false),
            writingStyle = ws,
            languagesDetected = jarr("languagesDetected"),
            topicKeywords = jarr("topicKeywords"),
            totalMessagesIn = o.optInt("totalMessagesIn", 0),
            totalMessagesOut = o.optInt("totalMessagesOut", 0),
            firstContactMs = o.optLong("firstContactMs", 0L),
            lastContactMs = o.optLong("lastContactMs", 0L),
            lastAnalyzedMs = o.optLong("lastAnalyzedMs", 0L),
            aiSummary = o.optString("aiSummary", "")
        )
    }

    /** Returns the AI summary for a contact ID, or empty string if no profile exists. */
    fun getAiSummary(contactId: String): String = loadProfile(contactId)?.aiSummary ?: ""
}
