package com.newax.aegis.engine.learning

import com.newax.aegis.SyncRuntime
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.db.entity.PersonEntity
import com.newax.aegis.db.entity.PersonFactEntity
import com.newax.aegis.engine.embedding.VectorStore

/**
 * Per-person fact store and cross-source importance tracker.
 * Backed by Room + SQLCipher instead of EncryptedSharedPreferences.
 *
 * Importance score (0–1):
 *   60% from distinct-source diversity + 40% from raw mention frequency.
 */
object PersonFactStore {

    private const val CROSS_SOURCE_THRESHOLD = 2
    private const val TOTAL_MENTION_THRESHOLD = 12
    private const val MAX_FACTS_PER_PERSON = 200
    private const val TOTAL_SCAN_SOURCES = 6       // mirrors ScanSource.entries.size

    data class PersonFact(
        val name: String,
        val fact: String,
        val category: String,
        val confidence: Float,
        val source: String,
        val timestampMs: Long
    )

    data class PersonImportance(
        val name: String,
        val score: Float,
        val sourceCount: Int,
        val totalMentions: Int,
        val lastSeenMs: Long
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /** Store a fact linked to a named person. Deduplicates by trigram Jaccard > 0.80. */
    fun addFact(db: NewaxDatabase, name: String, draft: LearningDraft) {
        kotlinx.coroutines.runBlocking {
            val personId = getOrCreateId(db, name)
            val existing = db.personFactDao().forPerson(personId)
            val alreadyExists = existing.any { trigramJaccard(it.fact.lowercase(), draft.fact.lowercase()) > 0.80f }
            if (!alreadyExists) {
                val rowId = db.personFactDao().insert(
                PersonFactEntity(
                    personId    = personId,
                    fact        = draft.fact,
                    category    = draft.category,
                    confidence  = draft.confidence,
                    source      = draft.source,
                    timestampMs = draft.timestampMs
                )
            )
            SyncRuntime.captureRecord(
                "person_facts", "$name\u0001${draft.fact}",
                listOf(
                    "personName" to name,
                    "fact" to draft.fact,
                    "category" to draft.category,
                    "confidence" to draft.confidence.toString(),
                    "source" to draft.source,
                    "timestampMs" to draft.timestampMs.toString()
                )
            )
            // Index for semantic search immediately if engine is ready; worker catches up otherwise
            val indexText = buildString {
                append(draft.fact)
                if (draft.category.isNotBlank()) append(" [${draft.category}]")
            }
            VectorStore.indexFact(db, rowId, indexText)

                val count = db.personFactDao().countForPerson(personId)
                if (count > MAX_FACTS_PER_PERSON) {
                    db.personFactDao().trimToLimit(personId, MAX_FACTS_PER_PERSON)
                }
            }
        }
    }

    fun factsFor(db: NewaxDatabase, name: String): List<PersonFact> {
        val personId = getPersonId(db, name) ?: return emptyList()
        return kotlinx.coroutines.runBlocking { db.personFactDao().forPerson(personId).map { it.toPersonFact(name) } }
    }

    /**
     * Record that this person was encountered in a scan source.
     * Increments per-source count, updates denormalized totals + importance score.
     */
    fun recordMention(db: NewaxDatabase, name: String, source: String) {
        val now = System.currentTimeMillis()
        kotlinx.coroutines.runBlocking {
            val personId = getOrCreateId(db, name)
            db.personMentionDao().incrementOrInsert(personId, source)
            val sc = db.personMentionDao().sourceCount(personId) ?: 1
            val tm = db.personMentionDao().totalMentions(personId) ?: 1
            val score = computeScore(sc, tm)
            db.personDao().updateStats(personId, sc, tm, score, now)
            capturePerson(db, name)
        }
    }

    fun getImportanceScore(db: NewaxDatabase, name: String): Float =
        kotlinx.coroutines.runBlocking { db.personDao().findByName(name)?.importanceScore ?: 0f }

    fun getPersonId(db: NewaxDatabase, name: String): Long? =
        kotlinx.coroutines.runBlocking { db.personDao().findByName(name)?.id }

    /** Top N people by importance score. */
    fun getTopPeople(db: NewaxDatabase, limit: Int = 20): List<PersonImportance> =
        kotlinx.coroutines.runBlocking { db.personDao().getTopPeople(limit).map { it.toPersonImportance() } }

    /** True if person crossed the threshold and hasn't had a profile built yet. */
    fun needsProfileBuild(db: NewaxDatabase, name: String): Boolean {
        return kotlinx.coroutines.runBlocking {
            val person = db.personDao().findByName(name) ?: return@runBlocking false
            if (person.profileBuilt) return@runBlocking false
            return@runBlocking person.sourceCount >= CROSS_SOURCE_THRESHOLD || person.totalMentions >= TOTAL_MENTION_THRESHOLD
        }
    }

    fun markProfileBuilt(db: NewaxDatabase, name: String) {
        kotlinx.coroutines.runBlocking { db.personDao().markProfileBuilt(name) }
        capturePerson(db, name)
    }

    fun getPeopleNeedingProfileBuild(db: NewaxDatabase): List<String> =
        kotlinx.coroutines.runBlocking { db.personDao().getPeopleNeedingProfileBuild(CROSS_SOURCE_THRESHOLD, TOTAL_MENTION_THRESHOLD).map { it.name } }

    /**
     * Full-text search across all person facts.
     * Query supports FTS4 MATCH syntax: "hospital", "work*", "Ahmed hospital".
     */
    fun searchFacts(db: NewaxDatabase, query: String, limit: Int = 50): List<PersonFact> =
        kotlinx.coroutines.runBlocking {
            if (query.isBlank()) return@runBlocking emptyList()
            val safe = query.trim().replace("\"", "")
            db.personFactDao().searchFts(safe, limit).map { entity ->
                entity.toPersonFact(resolvePersonName(db, entity.personId))
            }
        }

    fun resolveEntity(db: NewaxDatabase, name: String): PersonEntity? =
        kotlinx.coroutines.runBlocking { db.personDao().findByName(name) }

    // ── Internals ─────────────────────────────────────────────────────────────

    private val nameCache = HashMap<Long, String>(64)

    private fun resolvePersonName(db: NewaxDatabase, personId: Long): String {
        nameCache[personId]?.let { return it }
        // Fallback: scan from getTopPeople (not ideal, but search is rare)
        kotlinx.coroutines.runBlocking {
            db.personDao().getTopPeople(1000).forEach { nameCache[it.id] = it.name }
        }
        return nameCache[personId] ?: ""
    }

    private fun getOrCreateId(db: NewaxDatabase, name: String): Long {
        return kotlinx.coroutines.runBlocking {
            val existing = db.personDao().findByName(name)
            if (existing != null) return@runBlocking existing.id
            db.personDao().insertIfAbsent(PersonEntity(name = name))
            capturePerson(db, name)
            db.personDao().idForName(name) ?: -1L
        }
    }

    /**
     * Journal the person's full current state (LWW per name). Called only on
     * the paths that actually change the person row — create, stats update,
     * profile-built. Materialize never goes through this path (it writes DAOs
     * directly), so remote applies can't re-capture.
     */
    private fun capturePerson(db: NewaxDatabase, name: String) {
        val p = kotlinx.coroutines.runBlocking { db.personDao().findByName(name) } ?: return
        SyncRuntime.captureRecord(
            "persons", p.name,
            listOf(
                "name" to p.name,
                "importanceScore" to p.importanceScore.toString(),
                "sourceCount" to p.sourceCount.toString(),
                "totalMentions" to p.totalMentions.toString(),
                "lastSeenMs" to p.lastSeenMs.toString(),
                "profileBuilt" to p.profileBuilt.toString()
            )
        )
    }

    private fun computeScore(sourceCount: Int, totalMentions: Int): Float {
        val sourceFactor  = (sourceCount.toFloat() / TOTAL_SCAN_SOURCES).coerceIn(0f, 1f)
        val mentionFactor = (totalMentions.toFloat() / 50f).coerceIn(0f, 1f)
        return sourceFactor * 0.6f + mentionFactor * 0.4f
    }

    private fun PersonFactEntity.toPersonFact(name: String) = PersonFact(
        name        = name,
        fact        = fact,
        category    = category,
        confidence  = confidence,
        source      = source,
        timestampMs = timestampMs
    )

    private fun PersonEntity.toPersonImportance() = PersonImportance(
        name          = name,
        score         = importanceScore,
        sourceCount   = sourceCount,
        totalMentions = totalMentions,
        lastSeenMs    = lastSeenMs
    )

    private fun trigramJaccard(a: String, b: String): Float {
        if (a.length < 3 || b.length < 3) return 0f
        val ta = trigrams(a); val tb = trigrams(b)
        val inter = ta.intersect(tb).size
        val union = (ta + tb).size
        return if (union == 0) 0f else inter.toFloat() / union
    }

    private fun trigrams(s: String): Set<String> =
        (0..(s.length - 3)).map { s.substring(it, it + 3) }.toSet()
}
