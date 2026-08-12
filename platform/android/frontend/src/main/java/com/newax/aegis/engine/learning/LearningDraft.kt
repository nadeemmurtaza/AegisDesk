package com.newax.aegis.engine.learning

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LearningDraft(
    val id: String = UUID.randomUUID().toString(),
    val category: String,       // personal, family, work, health, finance, events, places, habits, contacts
    val fact: String,            // extracted fact — always safe, never raw sensitive values
    val source: String,          // "SMS from Ali" / "Call Log" / "Gallery Image" etc.
    val sourceSnippet: String,   // brief redacted excerpt for context
    val confidence: Float,       // 0.0–1.0
    val timestampMs: Long,
    val status: Status = Status.PENDING,
    val subjectName: String? = null   // who this fact is about (contact name), null = about the user
) {
    enum class Status { PENDING, APPROVED, REJECTED }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("category", category)
        put("fact", fact)
        put("source", source)
        put("sourceSnippet", sourceSnippet)
        put("confidence", confidence.toDouble())
        put("timestampMs", timestampMs)
        put("status", status.name)
        if (subjectName != null) put("subjectName", subjectName)
    }

    companion object {
        fun fromJson(o: JSONObject) = LearningDraft(
            id            = o.optString("id", UUID.randomUUID().toString()),
            category      = o.optString("category", "personal"),
            fact          = o.optString("fact", ""),
            source        = o.optString("source", ""),
            sourceSnippet = o.optString("sourceSnippet", ""),
            confidence    = o.optDouble("confidence", 0.7).toFloat(),
            timestampMs   = o.optLong("timestampMs", 0L),
            status        = runCatching { Status.valueOf(o.optString("status", "PENDING")) }.getOrDefault(Status.PENDING),
            subjectName   = o.optString("subjectName").takeIf { it.isNotEmpty() }
        )

        fun listFromJson(raw: String): List<LearningDraft> = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (_: Exception) { emptyList() }

        fun listToJson(drafts: List<LearningDraft>): String {
            val arr = JSONArray()
            drafts.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
