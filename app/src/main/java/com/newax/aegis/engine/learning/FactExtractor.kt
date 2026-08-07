package com.newax.aegis.engine.learning

import com.newax.aegis.engine.DocumentClassifier
import com.newax.aegis.engine.SensitiveInfoDetector
import com.newax.aegis.engine.ToneAnalyzer

/**
 * Extracts structured, privacy-safe facts from raw text.
 *
 * Improvements over v1:
 * - Sentence-level extraction (not single-pass whole-text)
 * - Subject-aware facts: knows WHO the fact is about from source context
 * - Clause extraction: captures surrounding words for richer output
 * - Specificity filter: rejects vague/generic facts before they become drafts
 * - subjectName field on every fact for PersonFactStore linking
 *
 * Never returns raw sensitive values — always uses redacted text.
 */
object FactExtractor {

    // ── Patterns ──────────────────────────────────────────────────────────────

    private val DATE_RE = Regex(
        """\b(\d{1,2}[/\-]\d{1,2}[/\-]\d{2,4}|\d{4}-\d{2}-\d{2}|(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)""" +
        """|(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+\d{1,2}(?:,\s*\d{4})?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val TIME_RE = Regex(
        """\b\d{1,2}:\d{2}\s*(?:am|pm)?|\b\d{1,2}\s*(?:am|pm)\b""",
        RegexOption.IGNORE_CASE
    )
    private val MEETING_RE = Regex(
        """\b(meeting|appointment|call|visit|interview|lunch|dinner|event|conference|milna|mulaqat)\b""",
        RegexOption.IGNORE_CASE
    )
    private val MEDICAL_RE = Regex(
        """\b(doctor|hospital|clinic|medicine|prescription|checkup|dr\.|physician|دوا|ڈاکٹر)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ADDRESS_RE = Regex(
        """\b\d+[A-Za-z]?\s+[A-Za-z\s]+(street|st|road|rd|avenue|ave|block|lane|sector|phase|dha|gulshan|defence|clifton|pechs|garden|town|colony|scheme|bahria|askari)\b""",
        RegexOption.IGNORE_CASE
    )
    private val PREFERENCE_RE = Regex(
        """\b(prefer|love|hate|like|dislike|always use|never use|usually|enjoy|mujhe pasand|mujhe nahe)\b.{0,60}""",
        RegexOption.IGNORE_CASE
    )
    private val MONEY_RE = Regex(
        """\b(?:rs\.?|pkr|usd|\$|£|€)\s*[\d,]+|\d[\d,]*\s*(?:rupees?|dollars?|lakhs?|crores?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val RELATION_RE = Regex(
        """\bmy\s+(brother|sister|mother|father|wife|husband|friend|boss|colleague|son|daughter|bhai|amma|abbu|ammi|abu|yaar|dost)\b""",
        RegexOption.IGNORE_CASE
    )
    // "Ahmed said he will come" / "Ahmed is going to" → subject hint
    private val SUBJECT_SAID_RE = Regex(
        """^([A-Z][a-z]{1,15}(?:\s[A-Z][a-z]{1,15})?)\s+(?:said|told|mentioned|wrote|asked|confirmed|replied)""",
        RegexOption.IGNORE_CASE
    )
    // "works at / lives in / studied at" — for contradiction detection later
    private val PREDICATE_RE = Regex(
        """([A-Z][a-z]{1,20}(?:\s[A-Z][a-z]{1,20})?)\s+(works?\s+(?:at|for)|lives?\s+in|studied?\s+at|married\s+to|moved?\s+to|born\s+in)\s+(.{2,50})""",
        RegexOption.IGNORE_CASE
    )
    private val TASK_RE = Regex(
        """\b(need to|must|have to|don't forget|reminder|yaad rakhna|bhoolna mat)\b.{0,60}""",
        RegexOption.IGNORE_CASE
    )

    // From-source context: "SMS from Ahmed Khan" → "Ahmed Khan"
    private val FROM_CONTEXT_RE = Regex(
        """(?:(?:SMS|message|email|call)\s+(?:from|to)|Contact:)\s+([A-Za-z][A-Za-z\s\.]{1,35}?)(?:\s*—|\s*,|\s*${'$'})""",
        RegexOption.IGNORE_CASE
    )

    // ── Data types ────────────────────────────────────────────────────────────

    data class ExtractedFact(
        val category: String,
        val fact: String,
        val confidence: Float,
        val subjectName: String? = null   // null = fact is about the user
    )

    // ── Main extraction ───────────────────────────────────────────────────────

    /**
     * Extract facts from a text block.
     *
     * @param text         raw text (may contain sensitive info — redacted internally)
     * @param sourceContext label for the source ("SMS from Ahmed Khan", "Call Log", etc.)
     * @param subjectName  caller-supplied override for who this is about
     */
    fun extract(
        text: String,
        sourceContext: String = "",
        subjectName: String? = null
    ): List<ExtractedFact> {
        if (text.isBlank() || text.length < 8) return emptyList()

        val sensitive = SensitiveInfoDetector.analyze(text)

        // Skip OTP / cryptographic secrets / extremely high-sensitivity content
        if (sensitive.dominantType == SensitiveInfoDetector.SensitiveType.OTP ||
            sensitive.dominantType == SensitiveInfoDetector.SensitiveType.PRIVATE_KEY ||
            sensitive.dominantType == SensitiveInfoDetector.SensitiveType.JWT_TOKEN ||
            sensitive.dominantType == SensitiveInfoDetector.SensitiveType.AWS_KEY ||
            sensitive.sensitivityScore > 0.85f) {
            return emptyList()
        }

        val redacted = sensitive.redactedText
        val tone     = ToneAnalyzer.analyze(text)
        val docType  = DocumentClassifier.classify(text)

        // Resolve who this is about
        val subject = subjectName
            ?: FROM_CONTEXT_RE.find(sourceContext)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() && isLikelyName(it) }

        val results = mutableListOf<ExtractedFact>()

        // Sentence-level extraction — each sentence processed independently
        splitSentences(redacted).forEach { sentence ->
            results += extractSentence(sentence, subject, tone, docType)
        }

        // Document-level fallback: urgent content that sentences missed
        if (tone.urgency > 0.65f && results.isEmpty() && redacted.length > 25) {
            val snippet = redacted.take(120).trim()
            results += ExtractedFact(
                "events",
                buildFact(subject, "Urgent message: \"$snippet\""),
                0.58f, subject
            )
        }

        // Specificity filter: drop vague or too-short facts
        return results.filter { isSpecific(it.fact) }.distinctBy { it.fact.lowercase().take(60) }
    }

    // ── Sentence-level extraction ─────────────────────────────────────────────

    private fun extractSentence(
        sentence: String,
        defaultSubject: String?,
        tone: ToneAnalyzer.ToneResult,
        docType: DocumentClassifier.ClassificationResult
    ): List<ExtractedFact> {
        val results = mutableListOf<ExtractedFact>()

        // Detect per-sentence subject override ("Ahmed said he will…")
        val sentSubject = SUBJECT_SAID_RE.find(sentence)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { isLikelyName(it) } ?: defaultSubject

        val dateMatch    = DATE_RE.find(sentence)
        val timeMatch    = TIME_RE.find(sentence)
        val meetingMatch = MEETING_RE.find(sentence)

        // 1. Events / appointments — need both a meeting word AND a time anchor
        if (meetingMatch != null && (dateMatch != null || timeMatch != null)) {
            val when_ = listOfNotNull(dateMatch?.value, timeMatch?.value).joinToString(" at ")
            val clause = clauseAround(sentence, meetingMatch.range.first, 50)
            val factBody = if (clause.length > 15) clause else "${meetingMatch.value} on $when_"
            results += ExtractedFact("events", buildFact(sentSubject, "$factBody — $when_"), 0.82f, sentSubject)
        } else if (dateMatch != null && tone.urgency > 0.25f) {
            // Urgency + date with no explicit meeting word — still worth noting
            val clause = clauseAround(sentence, dateMatch.range.first, 60)
            if (clause.length > 20) {
                results += ExtractedFact("events", buildFact(sentSubject, clause), 0.60f, sentSubject)
            }
        }

        // 2. Medical / health
        MEDICAL_RE.find(sentence)?.let { m ->
            val clause = clauseAround(sentence, m.range.first, 60)
            val when_ = listOfNotNull(dateMatch?.value, timeMatch?.value).joinToString(" ")
            val body = if (when_.isNotBlank()) "$clause on $when_" else clause
            results += ExtractedFact("health", buildFact(sentSubject, body.ifBlank { "Medical/health: ${m.value}" }), 0.72f, sentSubject)
        }

        // 3. Address / location
        ADDRESS_RE.find(sentence)?.let { m ->
            results += ExtractedFact("places", buildFact(sentSubject, "Address: ${m.value}"), 0.78f, sentSubject)
        }

        // 4. Relationship: "my brother Ahmed" → "[subject]'s brother is Ahmed"
        RELATION_RE.find(sentence)?.let { m ->
            val relation = m.groupValues[1].trim()
            val afterRel = sentence.substringAfter(m.value).trimStart(':',' ')
            val nameAfter = Regex("^([A-Z][a-z]{1,15}(?:\\s[A-Z][a-z]{1,15})?)").find(afterRel)?.value
            val factBody = if (nameAfter != null) "${m.value.trim()} is $nameAfter"
                           else "${m.value.trim()} mentioned"
            results += ExtractedFact("family", buildFact(sentSubject, factBody), 0.68f, sentSubject)
        }

        // 5. Predicate facts: "Ahmed works at Acme" — extract structured triple
        PREDICATE_RE.find(sentence)?.let { m ->
            val who = m.groupValues[1].trim()
            val pred = m.groupValues[2].trim()
            val obj = m.groupValues[3].trim().take(50)
            val resolvedSubject = if (isLikelyName(who)) who else sentSubject
            results += ExtractedFact("work", "$who $pred $obj", 0.88f, resolvedSubject)
        }

        // 6. Preferences / habits
        PREFERENCE_RE.find(sentence)?.let { m ->
            val snippet = sentence.take(120).trim()
            if (snippet.length > 25) {
                results += ExtractedFact("personal", buildFact(sentSubject, "Preference: \"$snippet\""), 0.65f, sentSubject)
            }
        }

        // 7. Financial mention — use doc type to avoid spurious matches
        if (MONEY_RE.containsMatchIn(sentence) &&
            docType.type in setOf(
                DocumentClassifier.DocType.PERSONAL_FINANCIAL,
                DocumentClassifier.DocType.BUSINESS_INVOICE,
                DocumentClassifier.DocType.RECEIPT,
                DocumentClassifier.DocType.FINANCIAL_CRYPTO
            )) {
            results += ExtractedFact("finance", buildFact(sentSubject, "Financial transaction (amount redacted)"), 0.65f, sentSubject)
        }

        // 8. Reminders / tasks
        TASK_RE.find(sentence)?.let { m ->
            val snippet = sentence.take(100).trim()
            if (snippet.length > 20) {
                results += ExtractedFact("events", buildFact(sentSubject, "Reminder: \"$snippet\""), 0.62f, sentSubject)
            }
        }

        return results
    }

    // ── Contact / call extractors (unchanged API, improved output) ────────────

    fun extractFromContact(
        name: String,
        organization: String?,
        phones: List<String>,
        emails: List<String>
    ): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()
        if (!organization.isNullOrBlank()) {
            facts += ExtractedFact("work", "$name works at $organization", 0.90f, name)
        }
        if (emails.isNotEmpty()) {
            facts += ExtractedFact("contacts", "$name — email: ${emails.first()}", 0.92f, name)
        }
        return facts
    }

    fun extractFromCall(
        name: String,
        typeLabel: String,
        durationSec: Long,
        wasFrequent: Boolean
    ): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()
        if (durationSec > 300) {
            facts += ExtractedFact("habits",
                "$name — long $typeLabel call (${durationSec / 60} min)", 0.72f, name)
        }
        if (wasFrequent) {
            facts += ExtractedFact("habits", "Frequent contact: $name", 0.78f, name)
        }
        return facts
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Prepend subject to fact body when known. */
    private fun buildFact(subject: String?, body: String): String =
        if (subject != null) "$subject — $body" else body

    /** Extract a readable clause centred around a match position. */
    private fun clauseAround(sentence: String, pos: Int, radius: Int): String {
        val start = maxOf(0, pos - radius / 3)
        val end   = minOf(sentence.length, pos + radius * 2 / 3)
        return sentence.substring(start, end).trim().trimEnd(',', ';')
    }

    /** Split into sentences on punctuation and newlines. */
    private fun splitSentences(text: String): List<String> =
        text.split(Regex("[.!?؟۔\n]+"))
            .map { it.trim() }
            .filter { it.length > 12 }

    /** A name has at least 2 letters and no digits. */
    private fun isLikelyName(s: String): Boolean =
        s.length >= 2 && s.any { it.isLetter() } && s.none { it.isDigit() } &&
        s.lowercase() !in setOf("sms", "call", "from", "to", "unknown", "gallery", "contact", "downloads")

    /**
     * Reject facts that are too short, end in generic labels, or have no meaningful content.
     * This is the primary quality gate — keeps the draft queue signal-rich.
     */
    private fun isSpecific(fact: String): Boolean {
        if (fact.length < 22) return false
        val lower = fact.lowercase()
        val genericSuffixes = listOf(
            "noted", "communication noted", "something about", "mentioned", "— null"
        )
        if (genericSuffixes.any { lower.endsWith(it) }) return false
        // Reject if body after "—" is very short (subject present but body empty)
        val dashIdx = fact.indexOf(" — ")
        if (dashIdx != -1 && fact.length - dashIdx < 10) return false
        return true
    }
}
