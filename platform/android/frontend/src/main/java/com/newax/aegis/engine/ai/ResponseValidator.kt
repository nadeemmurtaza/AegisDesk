package com.newax.aegis.engine.ai

object ResponseValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val cleanedResponse: String,
        val issues: List<String>,
        val confidence: Float
    )

    private val HALLUCINATION_MARKERS = listOf(
        "as of my knowledge cutoff",
        "i cannot access",
        "i don't have access to",
        "i'm not able to browse",
        "my training data",
        "as an ai language model",
        "i'm just an ai",
        "i cannot provide",
        "it's important to note that i"
    )

    private val REFUSAL_MARKERS = listOf(
        "i cannot help with",
        "i'm unable to assist",
        "i apologize, but i cannot",
        "that's not something i can"
    )

    private val UNCERTAINTY_MARKERS = listOf(
        "i think", "i believe", "i'm not sure", "i'm uncertain",
        "it's possible that", "you might want to verify"
    )

    private val EMPTY_PATTERNS = listOf(
        "ok", "sure", "yes", "no", "okay",
        "got it", "understood", "alright"
    )

    fun validate(response: String, query: String = ""): ValidationResult {
        val issues = mutableListOf<String>()
        var cleaned = response.trim()

        if (cleaned.isBlank()) {
            return ValidationResult(false, cleaned, listOf("Empty response"), 0f)
        }

        if (cleaned.length < 5 && EMPTY_PATTERNS.any { cleaned.lowercase() == it }) {
            issues.add("Response too short — may not answer the query")
        }

        val hallucinationCount = HALLUCINATION_MARKERS.count { cleaned.lowercase().contains(it) }
        if (hallucinationCount > 0) {
            issues.add("Response contains ${hallucinationCount} AI-awareness markers")
        }

        val refusalCount = REFUSAL_MARKERS.count { cleaned.lowercase().contains(it) }
        if (refusalCount > 0) {
            issues.add("Response contains refusal language")
        }

        val uncertaintyCount = UNCERTAINTY_MARKERS.count { cleaned.lowercase().contains(it) }
        if (uncertaintyCount >= 3) {
            issues.add("High uncertainty in response")
        }

        if (cleaned.length > 5000) {
            issues.add("Response may be too long")
        }

        val hasRepeatedLines = detectRepeatedContent(cleaned)
        if (hasRepeatedLines) {
            issues.add("Response contains repeated content")
            cleaned = deduplicateLines(cleaned)
        }

        val confidence = calculateConfidence(issues, cleaned)
        return ValidationResult(
            isValid = issues.none { it.contains("refusal") || it.contains("Empty") },
            cleanedResponse = cleaned,
            issues = issues,
            confidence = confidence
        )
    }

    fun sanitize(response: String): String {
        var result = response.trim()
        result = result.replace(Regex("(?i)as an AI language model,?\\s*"), "")
        result = result.replace(Regex("(?i)as of my knowledge cutoff[^.]*\\.\\s*"), "")
        result = result.replace(Regex("(?i)I'm just an AI[^.]*\\.\\s*"), "")
        return result.trim()
    }

    fun isHallucination(claim: String, knownFacts: List<String>): Float {
        val claimLower = claim.lowercase()
        val matchingFacts = knownFacts.count { fact ->
            fact.lowercase().let { fl ->
                claimLower.split(" ").filter { it.length > 3 }.any { token -> fl.contains(token) }
            }
        }
        return if (knownFacts.isEmpty()) 0.5f else 1f - (matchingFacts.toFloat() / knownFacts.size).coerceAtMost(1f)
    }

    fun extractFactualClaims(response: String): List<String> =
        response.split(".", "!", "?")
            .map { it.trim() }
            .filter { sentence ->
                sentence.length > 20 &&
                !HALLUCINATION_MARKERS.any { sentence.lowercase().contains(it) } &&
                !UNCERTAINTY_MARKERS.any { sentence.lowercase().startsWith(it) }
            }

    private fun detectRepeatedContent(text: String): Boolean {
        val lines = text.lines().filter { it.isNotBlank() }
        return lines.size != lines.toSet().size
    }

    private fun deduplicateLines(text: String): String {
        val seen = mutableSetOf<String>()
        return text.lines()
            .filter { line -> seen.add(line.trim()) }
            .joinToString("\n")
    }

    private fun calculateConfidence(issues: List<String>, response: String): Float {
        var confidence = 1f
        confidence -= issues.size * 0.1f
        confidence -= UNCERTAINTY_MARKERS.count { response.lowercase().contains(it) } * 0.05f
        return confidence.coerceIn(0f, 1f)
    }
}
