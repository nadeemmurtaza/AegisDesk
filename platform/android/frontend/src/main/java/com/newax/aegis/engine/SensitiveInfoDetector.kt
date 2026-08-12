package com.newax.aegis.engine

/**
 * Detects and redacts sensitive information in text without ever logging the raw values.
 *
 * Design principle: the raw sensitive VALUE is only returned when explicitly requested
 * by a caller that will secure it (e.g. autofill). All logging paths use redacted output.
 */
object SensitiveInfoDetector {

    enum class SensitiveType(val label: String) {
        OTP("OTP"),
        PASSWORD("Password"),
        API_KEY("API Key"),
        PRIVATE_KEY("Private Key"),
        JWT_TOKEN("JWT Token"),
        CREDIT_CARD("Credit Card"),
        CVV("CVV"),
        BANK_ACCOUNT("Bank Account"),
        IBAN("IBAN"),
        SSN("SSN"),
        NATIONAL_ID("National ID"),
        AADHAAR("Aadhaar"),
        PAN("PAN Card"),
        PASSPORT("Passport"),
        EMAIL("Email"),
        PHONE("Phone"),
        URL_WITH_TOKEN("URL with Token"),
        IP_ADDRESS("IP Address"),
        AWS_KEY("AWS Key"),
        GOOGLE_KEY("Google API Key"),
        SEED_PHRASE("Crypto Seed Phrase"),
        CNIC("CNIC")                       // Pakistani NIC
    }

    data class Detection(
        val type: SensitiveType,
        val start: Int,
        val end: Int,
        val rawValue: String,              // NEVER log this field
        val context: String                // surrounding text for intent (no raw value)
    )

    data class AnalysisResult(
        val detections: List<Detection>,
        val redactedText: String,
        val sensitivityScore: Float,       // 0.0 – 1.0
        val dominantType: SensitiveType?,
        val isSafeToLog: Boolean           // false if any high-sensitivity detections
    )

    private data class Pattern(val type: SensitiveType, val regex: Regex, val weight: Float)

    private val PATTERNS = listOf(
        // Crypto private keys
        Pattern(SensitiveType.PRIVATE_KEY,
            Regex("-----BEGIN\\s+(RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----[\\s\\S]+?-----END\\s+(RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----", RegexOption.IGNORE_CASE),
            1.0f),
        // JWT tokens
        Pattern(SensitiveType.JWT_TOKEN,
            Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            0.95f),
        // AWS Access Key
        Pattern(SensitiveType.AWS_KEY,
            Regex("(AKIA|ASIA|AROA|AIDA|ANPA|ANVA|APKA)[A-Z0-9]{16}"),
            0.95f),
        // Google API Key
        Pattern(SensitiveType.GOOGLE_KEY,
            Regex("AIza[0-9A-Za-z_-]{35}"),
            0.95f),
        // Credit card (Luhn-plausible 13-19 digit groups)
        Pattern(SensitiveType.CREDIT_CARD,
            Regex("\\b(?:4[0-9]{12}(?:[0-9]{3,6})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12}|(?:2131|1800|35\\d{3})\\d{11})\\b"),
            0.9f),
        // CVV / CVC / Security Code (3-4 digits near label)
        Pattern(SensitiveType.CVV,
            Regex("(?:cvv|cvc|csc|security code)[^\\d]{0,10}(\\d{3,4})", RegexOption.IGNORE_CASE),
            0.9f),
        // IBAN
        Pattern(SensitiveType.IBAN,
            Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{4}\\d{7}(?:[A-Z0-9]?){0,16}\\b"),
            0.85f),
        // SSN (US)
        Pattern(SensitiveType.SSN,
            Regex("\\b(?!000|666|9\\d{2})\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b"),
            0.9f),
        // Aadhaar (India 12-digit)
        Pattern(SensitiveType.AADHAAR,
            Regex("\\b[2-9]\\d{3}\\s?\\d{4}\\s?\\d{4}\\b"),
            0.85f),
        // PAN Card (India AAAAA9999A)
        Pattern(SensitiveType.PAN,
            Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b"),
            0.8f),
        // CNIC (Pakistan 00000-0000000-0)
        Pattern(SensitiveType.CNIC,
            Regex("\\b\\d{5}-\\d{7}-\\d\\b"),
            0.85f),
        // Passport (most formats)
        Pattern(SensitiveType.PASSPORT,
            Regex("\\b[A-Z]{1,2}\\d{6,9}\\b"),
            0.6f),
        // OTP context (digits near trigger words)
        Pattern(SensitiveType.OTP,
            Regex("(?:otp|one.time|verification|confirm|pin|code|passcode|token)[^\\d]{0,30}(\\d{4,8})", RegexOption.IGNORE_CASE),
            0.9f),
        // Standalone 6-digit codes (highly likely OTP in message context)
        Pattern(SensitiveType.OTP,
            Regex("(?<![\\d])\\b(\\d{6})\\b(?![\\d])"),
            0.6f),
        // Generic API keys (long hex/base64 strings ≥32 chars)
        Pattern(SensitiveType.API_KEY,
            Regex("[a-fA-F0-9]{32,64}|[A-Za-z0-9+/]{40,}={0,2}"),
            0.7f),
        // URL with embedded token/key/password params
        Pattern(SensitiveType.URL_WITH_TOKEN,
            Regex("https?://[^\\s]*(?:token|key|password|secret|auth|api_key)=[^\\s&]+", RegexOption.IGNORE_CASE),
            0.85f),
        // Email
        Pattern(SensitiveType.EMAIL,
            Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"),
            0.4f),
        // Phone (E.164 and local formats)
        Pattern(SensitiveType.PHONE,
            Regex("(?:\\+?\\d{1,3}[\\s.-]?)?\\(?\\d{2,4}\\)?[\\s.-]?\\d{3,4}[\\s.-]?\\d{4}"),
            0.35f),
        // IP Address (private ranges are more sensitive)
        Pattern(SensitiveType.IP_ADDRESS,
            Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b"),
            0.3f),
        // Crypto seed phrases (12/24 BIP39-like word patterns)
        Pattern(SensitiveType.SEED_PHRASE,
            Regex("\\b(?:[a-z]+ ){11}[a-z]+\\b|\\b(?:[a-z]+ ){23}[a-z]+\\b"),
            0.95f)
    )

    private val HIGH_SENSITIVITY = setOf(
        SensitiveType.PRIVATE_KEY, SensitiveType.JWT_TOKEN, SensitiveType.AWS_KEY,
        SensitiveType.GOOGLE_KEY, SensitiveType.CREDIT_CARD, SensitiveType.CVV,
        SensitiveType.SSN, SensitiveType.AADHAAR, SensitiveType.CNIC,
        SensitiveType.SEED_PHRASE, SensitiveType.OTP, SensitiveType.API_KEY
    )

    fun analyze(text: String): AnalysisResult {
        if (text.isBlank()) return AnalysisResult(emptyList(), text, 0f, null, true)

        val detections = mutableListOf<Detection>()
        val covered = mutableSetOf<IntRange>()

        for (pattern in PATTERNS) {
            for (match in pattern.regex.findAll(text)) {
                val range = match.range
                if (covered.any { it.contains(range.first) || it.contains(range.last) }) continue
                val surrounding = text.substring(maxOf(0, range.first - 30), minOf(text.length, range.last + 30))
                    .replace(match.value, "[…]")
                detections += Detection(
                    type = pattern.type,
                    start = range.first,
                    end = range.last + 1,
                    rawValue = match.value,
                    context = surrounding
                )
                covered += range
            }
        }

        val redacted = buildRedactedText(text, detections)
        val score = if (detections.isEmpty()) 0f else
            (detections.map { d -> PATTERNS.first { it.type == d.type }.weight }.max()).coerceIn(0f, 1f)
        val dominant = detections.maxByOrNull { d -> PATTERNS.first { it.type == d.type }.weight }?.type
        val isSafe = detections.none { it.type in HIGH_SENSITIVITY }

        return AnalysisResult(detections.sortedBy { it.start }, redacted, score, dominant, isSafe)
    }

    /** Returns text with all detected sensitive values replaced by [REDACTED:TYPE] markers. */
    fun redact(text: String): String = analyze(text).redactedText

    /** True if text contains any OTP-like pattern. */
    fun containsOtp(text: String): Boolean =
        analyze(text).detections.any { it.type == SensitiveType.OTP }

    /** Extracts the first OTP found, or null. Caller must secure this value. */
    fun extractOtp(text: String): String? =
        analyze(text).detections.firstOrNull { it.type == SensitiveType.OTP }?.rawValue

    private fun buildRedactedText(text: String, detections: List<Detection>): String {
        if (detections.isEmpty()) return text
        val sb = StringBuilder()
        var cursor = 0
        for (d in detections.sortedBy { it.start }) {
            if (d.start > cursor) sb.append(text, cursor, d.start)
            sb.append("[REDACTED:${d.type.label}]")
            cursor = d.end
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    /** Human-readable summary of what was found, safe to show the user/AI. */
    fun summary(result: AnalysisResult): String {
        if (result.detections.isEmpty()) return "No sensitive content detected."
        val counts = result.detections.groupBy { it.type }.mapValues { it.value.size }
        val items = counts.entries.joinToString(", ") { "${it.value}x ${it.key.label}" }
        val level = when {
            result.sensitivityScore >= 0.9f -> "CRITICAL"
            result.sensitivityScore >= 0.7f -> "HIGH"
            result.sensitivityScore >= 0.4f -> "MEDIUM"
            else                            -> "LOW"
        }
        return "[$level sensitivity] Detected: $items"
    }
}
