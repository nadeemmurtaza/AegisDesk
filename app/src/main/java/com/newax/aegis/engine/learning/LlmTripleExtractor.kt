package com.newax.aegis.engine.learning

import android.util.Log
import com.newax.aegis.assistant.LiteRtOfflineModel
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.TripleEntity
import com.newax.aegis.engine.SensitiveInfoDetector
import com.newax.aegis.engine.embedding.VectorStore
import org.json.JSONArray

object LlmTripleExtractor {

    private const val TAG          = "AegisTripExtract"
    private const val MIN_TEXT_LEN = 80
    private const val MAX_TEXT_LEN = 1200
    private const val MIN_CONF     = 0.55f

    @Volatile private var model: LiteRtOfflineModel? = null

    fun bind(m: LiteRtOfflineModel?) { model = m }
    fun isReady(): Boolean = model?.isReady == true

    suspend fun extract(
        text: String,
        sourceContext: String,
        subjectHint: String?
    ): List<TripleEntity> {
        val m = model ?: return emptyList()
        if (!m.isReady || text.length < MIN_TEXT_LEN) return emptyList()

        // SECURITY: redact before sending; skip high-sensitivity content
        val analysis = SensitiveInfoDetector.analyze(text)
        if (analysis.sensitivityScore > 0.75f) return emptyList()
        val safeText = analysis.redactedText.take(MAX_TEXT_LEN)

        return try {
            val response = m.complete(buildPrompt(safeText, sourceContext, subjectHint), null)
            parseJson(response, sourceContext)
        } catch (e: Exception) {
            Log.w(TAG, "Triple extract error: ${e.message}")
            emptyList()
        }
    }

    fun save(db: AegisDatabase, triples: List<TripleEntity>) {
        if (triples.isEmpty()) return
        triples.forEach { triple ->
            val id = db.tripleDao().insert(triple)
            VectorStore.indexTriple(db, id, triple)
        }
    }

    /** Persist a manually-created edge (e.g. from ProposedAction.UpdateGraph). */
    fun saveEdge(db: AegisDatabase, from: String, relation: String, to: String, source: String = "manual") {
        db.tripleDao().insert(
            TripleEntity(
                subject     = from,
                predicate   = relation.lowercase().replace(' ', '_'),
                objectValue = to,
                confidence  = 1.0f,
                source      = source,
                createdMs   = System.currentTimeMillis()
            )
        )
    }

    fun about(db: AegisDatabase, entity: String): List<TripleEntity> =
        db.tripleDao().involving(entity)

    fun count(db: AegisDatabase): Int = db.tripleDao().count()

    // ── Prompt ───────────────────────────────────────────────────────────────

    private fun buildPrompt(text: String, source: String, subject: String?): String = buildString {
        appendLine("Extract knowledge graph triples from the message. Output a JSON array only — no other text.")
        appendLine("""Each item: {"subject":"entity name","predicate":"relationship","object":"value or entity","confidence":0.0-1.0}""")
        appendLine("Predicates: works_at, worked_at, lives_in, knows, owns, likes, dislikes, birthday_on, studies_at, related_to, called, texted, met, hobby_is, drives, has_condition, takes_medication, allergic_to, member_of")
        appendLine("Rules: subjects and objects are proper nouns or concepts. Skip raw credentials, card/account numbers, OTPs. Return [] if nothing useful.")
        if (source.isNotBlank()) appendLine("SOURCE: $source")
        if (subject != null)     appendLine("CONTEXT PERSON: $subject")
        appendLine("MESSAGE: $text")
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private fun parseJson(response: String, source: String): List<TripleEntity> {
        val start = response.indexOf('[')
        val end   = response.lastIndexOf(']')
        if (start == -1 || end <= start) return emptyList()
        val now = System.currentTimeMillis()

        return try {
            val arr = JSONArray(response.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { i ->
                val obj     = arr.optJSONObject(i) ?: return@mapNotNull null
                val subject = obj.optString("subject", "").trim()
                val pred    = obj.optString("predicate", "").trim().lowercase().replace(' ', '_')
                val objVal  = obj.optString("object", "").trim()
                val conf    = obj.optDouble("confidence", 0.0).toFloat()

                if (subject.length < 2 || pred.isEmpty() || objVal.length < 2) return@mapNotNull null
                if (conf < MIN_CONF || pred !in VALID_PREDICATES)               return@mapNotNull null

                TripleEntity(
                    subject     = subject,
                    predicate   = pred,
                    objectValue = objVal,
                    confidence  = conf.coerceIn(0f, 1f),
                    source      = source,
                    createdMs   = now
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Triple JSON parse failed: ${e.message}")
            emptyList()
        }
    }

    private val VALID_PREDICATES = setOf(
        "works_at", "worked_at", "lives_in", "knows", "owns", "likes", "dislikes",
        "birthday_on", "studies_at", "related_to", "called", "texted", "met",
        "hobby_is", "drives", "has_condition", "takes_medication", "allergic_to", "member_of"
    )
}
