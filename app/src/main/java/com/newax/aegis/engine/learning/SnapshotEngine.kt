package com.newax.aegis.engine.learning

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.PersonSnapshot
import com.newax.aegis.engine.HabitTracker
import com.newax.aegis.engine.bus.AegisEvent
import com.newax.aegis.engine.bus.AegisEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SnapshotEngine {

    data class PersonSnapshotData(
        val personId: Long,
        val topFacts: List<String>,
        val sourceCount: Int,
        val totalMentions: Int,
        val confidence: Int
    )

    suspend fun compilePersonSnapshot(personId: Long, db: AegisDatabase): PersonSnapshotData =
        withContext(Dispatchers.IO) {
            val facts = db.personFactDao().forPerson(personId)
                .filter { it.confidence > 0.5f }
                .sortedByDescending { it.confidence }
                .take(20)

            val sourceCount = db.personMentionDao().sourceCount(personId)
            val totalMentions = db.personMentionDao().totalMentions(personId)
            val confidence = (facts.size * 4 + sourceCount * 10).coerceAtMost(95)

            val topicsFact = facts.joinToString(", ") { it.category }.take(100)
            val existing = db.personRegistryDao().snapshot(personId)
            val now = System.currentTimeMillis()
            if (existing != null) {
                db.personRegistryDao().upsertSnapshot(
                    existing.copy(
                        recentTopics = topicsFact,
                        importanceScore = confidence,
                        snapshotUpdatedMs = now
                    )
                )
            } else {
                db.personRegistryDao().upsertSnapshot(
                    PersonSnapshot(
                        personEntityId = personId,
                        displayName = facts.firstOrNull()?.fact?.take(40) ?: "Unknown",
                        recentTopics = topicsFact,
                        importanceScore = confidence,
                        snapshotUpdatedMs = now
                    )
                )
            }

            AegisEventBus.emit(AegisEvent.SnapshotCompiled("person", personId.toString()))
            PersonSnapshotData(personId, facts.map { it.fact }, sourceCount, totalMentions, confidence)
        }

    suspend fun compileTopPersonSnapshots(db: AegisDatabase, limit: Int = 50): Int =
        withContext(Dispatchers.IO) {
            val persons = db.personDao().getTopPeople(limit)
            var compiled = 0
            for (person in persons) {
                runCatching { compilePersonSnapshot(person.id, db) }
                compiled++
            }
            compiled
        }

    fun compileAppSnapshot(packageName: String): HabitTracker.AppHabitPattern? =
        HabitTracker.getPatternForPackage(packageName)?.also {
            AegisEventBus.emit(AegisEvent.SnapshotCompiled("app", packageName))
        }
}
