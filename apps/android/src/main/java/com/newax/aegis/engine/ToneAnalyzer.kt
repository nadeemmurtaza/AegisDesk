package com.newax.aegis.engine

/** Pure keyword/frequency tone analysis. No ML required. */
object ToneAnalyzer {

    enum class Sentiment { POSITIVE, NEGATIVE, NEUTRAL }
    enum class Formality { FORMAL, INFORMAL, UNKNOWN }
    enum class Intent { REQUEST, COMPLAINT, QUESTION, INFORMATION, GREETING, THREAT, ADVERTISEMENT }

    data class ToneProfile(
        val urgency: Float,           // 0.0–1.0
        val threat: Float,            // 0.0–1.0
        val phishingRisk: Float,      // 0.0–1.0
        val spamRisk: Float,          // 0.0–1.0
        val sentiment: Sentiment,
        val formality: Formality,
        val intent: Intent,
        val capsRatio: Float,         // fraction of letters that are uppercase
        val exclamationCount: Int,
        val summary: String           // human-readable one-liner
    )

    private val URGENCY_KEYWORDS = setOf(
        "urgent", "asap", "immediately", "right now", "deadline", "expires", "expiring",
        "act now", "limited time", "don't delay", "last chance", "hurry", "critical",
        "time sensitive", "action required", "respond now", "important notice",
        "24 hours", "48 hours", "فوری", "ابھی", "اہم"           // Urdu urgency words
    )

    private val THREAT_KEYWORDS = setOf(
        "legal action", "lawsuit", "police", "arrest", "suspend", "suspended", "block",
        "blocked", "banned", "terminate", "terminated", "consequences", "penalty", "penalize",
        "overdue", "deactivate", "deactivated", "freezing", "frozen", "report you",
        "take action against", "will be prosecuted"
    )

    private val PHISHING_KEYWORDS = setOf(
        "verify your account", "confirm your account", "unusual activity", "suspicious login",
        "security alert", "click here to verify", "your account has been", "update your password",
        "reset password", "login attempt", "failed login", "unauthorized access",
        "validate your", "authenticate your", "confirm identity", "reauthenticate"
    )

    private val SPAM_KEYWORDS = setOf(
        "free", "win", "winner", "congratulations", "you have been selected", "lottery",
        "prize", "million", "billion", "offshore", "inheritance", "claim your",
        "100%", "guaranteed", "no risk", "earn money", "make money fast",
        "work from home", "get paid", "click here", "subscribe now", "unsubscribe"
    )

    private val POSITIVE_KEYWORDS = setOf(
        "thank", "thanks", "appreciate", "great", "excellent", "wonderful", "happy",
        "pleased", "glad", "good news", "success", "achieved", "congratulations",
        "welcome", "love", "enjoy", "helpful", "perfect", "brilliant", "awesome"
    )

    private val NEGATIVE_KEYWORDS = setOf(
        "problem", "issue", "failed", "error", "wrong", "broken", "not working",
        "disappointed", "frustrated", "angry", "upset", "hate", "terrible",
        "worst", "useless", "incompetent", "bad", "horrible", "annoyed", "complaint"
    )

    private val FORMAL_SIGNALS = setOf(
        "dear", "regards", "sincerely", "respectfully", "to whom it may concern",
        "pursuant to", "herein", "attached please find", "enclosed", "kindly",
        "whereas", "therefore", "furthermore", "however", "nevertheless"
    )

    private val INFORMAL_SIGNALS = setOf(
        "hey", "hi there", "what's up", "gonna", "wanna", "u ", " ur ", "lol",
        "omg", "btw", "idk", "tbh", "ngl", "bruh", "dude", "bro", "sis", "fam"
    )

    private val GREETING_KEYWORDS = setOf(
        "hello", "hi", "hey", "good morning", "good afternoon", "good evening",
        "greetings", "salaam", "assalam", "how are you", "hope you"
    )

    private val COMPLAINT_KEYWORDS = setOf(
        "complaint", "complain", "unacceptable", "not satisfied", "refund",
        "return", "broken", "damaged", "never received", "didn't receive",
        "still waiting", "no response", "ignored", "disappointed"
    )

    private val ADVERTISEMENT_KEYWORDS = setOf(
        "offer", "discount", "sale", "% off", "buy now", "order now", "shop now",
        "deal", "promo", "promotion", "special offer", "limited offer", "coupon",
        "voucher", "cashback", "reward", "points", "exclusive"
    )

    fun analyze(text: String): ToneProfile {
        if (text.isBlank()) return ToneProfile(
            urgency = 0f, threat = 0f, phishingRisk = 0f, spamRisk = 0f,
            sentiment = Sentiment.NEUTRAL, formality = Formality.UNKNOWN, intent = Intent.INFORMATION,
            capsRatio = 0f, exclamationCount = 0, summary = "Empty input"
        )

        val lower = text.lowercase()
        val words = lower.split(Regex("[\\s,.!?;:\"'()\\[\\]{}]+")).filter { it.isNotBlank() }

        val urgency = scoreKeywords(lower, words, URGENCY_KEYWORDS, 0.15f)
        val threat = scoreKeywords(lower, words, THREAT_KEYWORDS, 0.2f)
        val phishing = scoreKeywords(lower, words, PHISHING_KEYWORDS, 0.12f)
        val spam = scoreKeywords(lower, words, SPAM_KEYWORDS, 0.1f)

        val posScore = scoreKeywords(lower, words, POSITIVE_KEYWORDS, 0.15f)
        val negScore = scoreKeywords(lower, words, NEGATIVE_KEYWORDS, 0.15f)
        val sentiment = when {
            posScore > negScore + 0.1f -> Sentiment.POSITIVE
            negScore > posScore + 0.1f -> Sentiment.NEGATIVE
            else -> Sentiment.NEUTRAL
        }

        val formalScore = scoreKeywords(lower, words, FORMAL_SIGNALS, 0.2f)
        val informalScore = scoreKeywords(lower, words, INFORMAL_SIGNALS, 0.2f)
        val formality = when {
            formalScore > 0.2f -> Formality.FORMAL
            informalScore > 0.2f -> Formality.INFORMAL
            else -> Formality.UNKNOWN
        }

        val hasQuestion = text.contains('?') || words.any { it in setOf("how", "what", "when", "where", "why", "who", "which", "can you", "could you", "would you") }
        val intent = when {
            threat > 0.4f -> Intent.THREAT
            scoreKeywords(lower, words, COMPLAINT_KEYWORDS, 0.15f) > 0.3f -> Intent.COMPLAINT
            scoreKeywords(lower, words, ADVERTISEMENT_KEYWORDS, 0.1f) > 0.3f -> Intent.ADVERTISEMENT
            scoreKeywords(lower, words, GREETING_KEYWORDS, 0.2f) > 0.2f && text.length < 120 -> Intent.GREETING
            hasQuestion -> Intent.QUESTION
            words.any { it in setOf("please", "kindly", "could you", "would you", "can you", "requesting", "request") } -> Intent.REQUEST
            else -> Intent.INFORMATION
        }

        val letters = text.filter { it.isLetter() }
        val capsRatio = if (letters.isEmpty()) 0f else letters.count { it.isUpperCase() }.toFloat() / letters.length
        val exclamations = text.count { it == '!' }

        // Phishing boost: high urgency + credentials/link mention
        val phishingFinal = (phishing + if (urgency > 0.4f && phishing > 0.1f) 0.2f else 0f).coerceIn(0f, 1f)
        // Spam boost: lots of ! and CAPS
        val spamFinal = (spam + (capsRatio * 0.2f) + (exclamations * 0.03f)).coerceIn(0f, 1f)

        val summary = buildSummary(urgency, threat, phishingFinal, spamFinal, sentiment, formality, intent, capsRatio, exclamations)

        return ToneProfile(
            urgency = urgency, threat = threat, phishingRisk = phishingFinal, spamRisk = spamFinal,
            sentiment = sentiment, formality = formality, intent = intent,
            capsRatio = capsRatio, exclamationCount = exclamations, summary = summary
        )
    }

    private fun scoreKeywords(text: String, words: List<String>, keywords: Set<String>, perHitWeight: Float): Float {
        var score = 0f
        for (kw in keywords) {
            if (kw.contains(' ')) {
                if (text.contains(kw)) score += perHitWeight * 1.5f
            } else {
                if (kw in words) score += perHitWeight
            }
        }
        return score.coerceIn(0f, 1f)
    }

    private fun buildSummary(
        urgency: Float, threat: Float, phishing: Float, spam: Float,
        sentiment: Sentiment, formality: Formality, intent: Intent,
        capsRatio: Float, exclamations: Int
    ): String {
        val flags = mutableListOf<String>()
        if (phishing > 0.4f) flags += "⚠ PHISHING RISK"
        if (threat > 0.5f) flags += "⚠ THREATENING"
        if (spam > 0.5f) flags += "SPAM LIKELY"
        if (urgency > 0.5f) flags += "URGENT"
        if (capsRatio > 0.4f) flags += "EXCESSIVE CAPS"
        if (exclamations > 3) flags += "$exclamations exclamations"

        val base = "${sentiment.name} / ${formality.name} / ${intent.name}"
        return if (flags.isEmpty()) base else "$base | ${flags.joinToString(", ")}"
    }

    /** Quick alarm-level check — anything above 0.5 on any risk dimension. */
    fun isAlarm(profile: ToneProfile): Boolean =
        profile.phishingRisk > 0.5f || profile.threat > 0.5f || profile.spamRisk > 0.6f
}
