package com.newax.aegis.engine.learning

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.PersonEntity
import com.newax.aegis.db.entity.PersonFactEntity

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
    fun addFact(db: AegisDatabase, name: String, draft: LearningDraft) {
        val personId = getOrCreateId(db, name)
        val existing = db.personFactDao().forPerson(personId)
        val alreadyExists = existing.any { trigramJaccard(it.fact.lowercase(), draft.fact.lowercase()) > 0.80f }
        if (!alreadyExists) {
            db.personFactDao().insert(
                PersonFactEntity(
                    personId    = personId,
                    fact        = draft.fact,
                    category    = draft.category,
                    confidence  = draft.confidence,
                    source      = draft.source,
                    timestampMs = draft.timestampMs
                )
            )
            // Trim to max limit (oldest removed)
            val count = db.personFactDao().countForPerson(personId)
            if (count > MAX_FACTS_PER_PERSON) {
                db.personFactDao().trimToLimit(personId, MAX_FACTS_PER_PERSON)
            }
        }
    }

    /** All facts for a person, newest first. */
    fun factsFor(db: AegisDatabase, name: String): List<PersonFact> {
        val personId = db.personDao().idForName(name) ?: return emptyList()
        return db.personFactDao().forPerson(personId).map { it.toPersonFact(name) }
    }

    /**
     * Record that this person was encountered in a scan source.
     * Increments per-source count, updates denormalized totals + importance score.
     */
    fun recordMention(db: AegisDatabase, name: String, source: String) {
        db.runInTransaction {
            val personId = getOrCreateId(db, name)
            db.personMentionDao().incrementOrInsert(personId, source)
            val sourceCount    = db.personMentionDao().sourceCount(personId)
            val totalMentions  = db.personMentionDao().totalMentions(personId)
            val score          = computeScore(sourceCount, totalMentions)
            db.personDao().updateStats(
                id             = personId,
                sourceCount    = sourceCount,
                totalMentions  = totalMentions,
                importanceScore = score,
                lastSeenMs     = System.currentTimeMillis()
            )
        }
    }

    /** Composite importance score 0–1. */
    fun getImportanceScore(db: AegisDatabase, name: String): Float {
        val person = db.personDao().findByName(name) ?: return 0f
        return person.importanceScore
    }

    /** Top N people by importance score. */
    fun getTopPeople(db: AegisDatabase, limit: Int = 20): List<PersonImportance> =
        db.personDao().getTopPeople(limit).map { it.toPersonImportance() }

    /** True if person crossed the threshold and hasn't had a profile built yet. */
    fun needsProfileBuild(db: AegisDatabase, name: String): Boolean {
        val person = db.personDao().findByName(name) ?: return false
        if (person.profileBuilt) return false
        return person.sourceCount >= CROSS_SOURCE_THRESHOLD || person.totalMentions >= TOTAL_MENTION_THRESHOLD
    }

    fun markProfileBuilt(db: AegisDatabase, name: String) =
        db.personDao().markProfileBuilt(name)

    fun getPeopleNeedingProfileBuild(db: AegisDatabase): List<String> =
        db.personDao().getPeopleNeedingProfileBuild(CROSS_SOURCE_THRESHOLD, TOTAL_MENTION_THRESHOLD)
            .map { it.name }

    /**
     * Full-text search across all person facts.
     * Query supports FTS4 MATCH syntax: "hospital", "work*", "Ahmed hospital".
     */
    fun searchFacts(db: AegisDatabase, query: String, limit: Int = 50): List<PersonFact> {
        if (query.isBlank()) return emptyList()
        val safe = query.trim().replace("\"", "")   // strip any embedded quotes
        return db.personFactDao().searchFts(safe, limit).map { entity ->
            val name = db.personDao().findByName("") // resolve by personId
            // Load name from persons table
            entity.toPersonFact(resolvePersonName(db, entity.personId))
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private val nameCache = HashMap<Long, String>(64)

    private fun resolvePersonName(db: AegisDatabase, personId: Long): String {
        nameCache[personId]?.let { return it }
        // Fallback: scan from getTopPeople (not ideal, but search is rare)
        db.personDao().getTopPeople(1000).forEach { nameCache[it.id] = it.name }
        return nameCache[personId] ?: ""
    }

    private fun getOrCreateId(db: AegisDatabase, name: String): Long {
        val id = db.personDao().insertIfAbsent(PersonEntity(name = name))
        return if (id > 0L) id else db.personDao().idForName(name)!!
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
