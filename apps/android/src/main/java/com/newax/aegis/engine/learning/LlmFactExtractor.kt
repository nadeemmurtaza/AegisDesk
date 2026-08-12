package com.newax.aegis.engine.learning

import android.util.Log
import com.newax.aegis.model.ModelProvider
import com.newax.aegis.model.ModelRequest
import com.newax.aegis.model.ModelState
import com.newax.aegis.engine.SensitiveInfoDetector
import org.json.JSONArray

/**
 * LLM-powered fact extraction using the already-loaded on-device model.
 *
 * Shares the same [ModelProvider] instance as the chat UI — no second engine is
 * created. Calls queue behind the engine's generation mutex, so chat and extraction
 * never run simultaneously (safe, no extra RAM).
 *
 * Use as a fallback / supplement to [FactExtractor]:
 *   - Only fires on texts longer than [MIN_TEXT_LEN]
 *   - Sensitivity gate: never sends high-sensitivity content to the model
 *   - Falls back to empty list silently if model not bound or not ready
 */
object LlmFactExtractor {

    private const val TAG          = "NewaxLlmExtract"
    private const val MIN_TEXT_LEN = 80
    private const val MAX_TEXT_LEN = 1200
    private const val MIN_CONF     = 0.50f

    @Volatile private var model: ModelProvider? = null

    /** Called from MainViewModel after the offline model finishes loading. */
    fun bind(m: ModelProvider?) { model = m }

    fun isReady(): Boolean = model?.state?.value == ModelState.READY

    /**
     * Extract facts from [text] using the on-device LLM.
     * Returns empty list on any failure — callers must treat this as best-effort.
     *
     * Must be called from a coroutine (suspend) — use runBlocking on Worker threads.
     */
    suspend fun extract(
        text: String,
        sourceContext: String,
        subjectName: String?
    ): List<FactExtractor.ExtractedFact> {
        val m = model ?: return emptyList()
        if (!isReady() || text.length < MIN_TEXT_LEN) return emptyList()

        // SECURITY: redact before sending; skip extremely sensitive content
        val analysis = SensitiveInfoDetector.analyze(text)
        if (analysis.sensitivityScore > 0.75f) return emptyList()
        val safeText = analysis.redactedText.take(MAX_TEXT_LEN)

        return try {
            val response = m.complete(ModelRequest(text = buildPrompt(safeText, sourceContext, subjectName))).text
            parseJson(response)
        } catch (e: Exception) {
            Log.w(TAG, "LLM extract error: ${e.message}")
            emptyList()
        }
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    private fun buildPrompt(text: String, source: String, subject: String?): String = buildString {
        appendLine("Extract facts from the message. Output a JSON array only — no other text.")
        appendLine("""Each item: {"category":"work|health|events|family|places|finance|habits|personal|contacts","fact":"concise third-person statement","confidence":0.0-1.0,"subject":"person name or null"}""")
        appendLine("Rules: omit OTPs, passwords, raw account/card numbers, raw money amounts. Return [] if nothing useful.")
        appendLine()
        if (source.isNotBlank()) appendLine("SOURCE: $source")
        if (subject != null)     appendLine("ABOUT: $subject")
        appendLine("MESSAGE: $text")
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private fun parseJson(response: String): List<FactExtractor.ExtractedFact> {
        val start = response.indexOf('[')
        val end   = response.lastIndexOf(']')
        if (start == -1 || end <= start) return emptyList()

        return try {
            val arr = JSONArray(response.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { i ->
                val obj      = arr.optJSONObject(i) ?: return@mapNotNull null
                val category = obj.optString("category", "personal").trim()
                val fact     = obj.optString("fact", "").trim()
                val conf     = obj.optDouble("confidence", 0.0).toFloat()
                val subject  = obj.optString("subject").takeIf {
                    it.isNotEmpty() && it != "null" && it != "NULL"
                }

                if (fact.length < 15 || conf < MIN_CONF) return@mapNotNull null
                if (category !in VALID_CATEGORIES)        return@mapNotNull null

                FactExtractor.ExtractedFact(category, fact, conf.coerceIn(0f, 1f), subject)
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed in LLM response: ${e.message}")
            emptyList()
        }
    }

    private val VALID_CATEGORIES = setOf(
        "work", "health", "events", "family", "places",
        "finance", "habits", "personal", "contacts"
    )
}
