package com.newax.aegis.db.migration

import android.content.Context
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.KvStoreEntity
import com.newax.aegis.db.entity.LearningDraftEntity
import com.newax.aegis.db.entity.PersonEntity
import com.newax.aegis.db.entity.PersonFactEntity
import com.newax.aegis.db.entity.PersonMentionEntity
import com.newax.aegis.memory.EncryptedMemory
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-shot worker that imports legacy EncryptedSharedPreferences data into Room.
 * Runs once on first launch after the DB is added; marks itself done via kv_store.
 * If migration fails, it retries (WorkManager Result.retry) with backoff.
 */
class LegacyMigrationWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    private val TAG = "AegisMigration"
    private val DONE_KEY = "migration_v1_done"

    override fun doWork(): Result {
        val db = try { AegisDatabase.get } catch (_: Exception) {
            Log.w(TAG, "DB not ready, will retry")
            return Result.retry()
        }

        // Already done?
        if (db.kvStoreDao().get(DONE_KEY) == "1") {
            Log.d(TAG, "Migration already completed")
            return Result.success()
        }

        return try {
            val memory = EncryptedMemory(applicationContext)
            migrateDrafts(db, memory)
            migratePersonFacts(db, memory)
            migratePersonMentions(db, memory)
            db.kvStoreDao().put(KvStoreEntity(DONE_KEY, "1"))
            Log.d(TAG, "Migration complete")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Migration error: ${e.message}")
            Result.retry()
        }
    }

    private fun migrateDrafts(db: AegisDatabase, memory: EncryptedMemory) {
        val raw = memory.getRaw("learning_drafts") ?: return
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        val entities = (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                LearningDraftEntity(
                    id            = o.optString("id", java.util.UUID.randomUUID().toString()),
                    category      = o.optString("category", "personal"),
                    fact          = o.optString("fact", ""),
                    source        = o.optString("source", ""),
                    sourceSnippet = o.optString("sourceSnippet", ""),
                    confidence    = o.optDouble("confidence", 0.7).toFloat(),
                    status        = o.optString("status", "PENDING"),
                    subjectName   = o.optString("subjectName").takeIf { it.isNotEmpty() },
                    timestampMs   = o.optLong("timestampMs", 0L)
                )
            }.getOrNull()
        }
        if (entities.isNotEmpty()) {
            db.learningDraftDao().insertAll(entities)
            Log.d(TAG, "Migrated ${entities.size} drafts")
        }
    }

    private fun migratePersonFacts(db: AegisDatabase, memory: EncryptedMemory) {
        val indexRaw = memory.getRaw("pf_index") ?: return
        val names = runCatching {
            val arr = JSONArray(indexRaw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())

        var totalFacts = 0
        for (name in names) {
            val slug = name.lowercase().replace(Regex("\\s+"), "_").take(30)
            val factsRaw = memory.getRaw("pf_$slug") ?: continue
            val arr = runCatching { JSONArray(factsRaw) }.getOrNull() ?: continue

            // Ensure person exists
            val personId = db.personDao().insertIfAbsent(PersonEntity(name = name))
                .let { id -> if (id > 0L) id else db.personDao().idForName(name) ?: continue }

            val facts = (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    PersonFactEntity(
                        personId    = personId,
                        fact        = o.getString("fact"),
                        category    = o.optString("category", "personal"),
                        confidence  = o.optDouble("confidence", 0.7).toFloat(),
                        source      = o.optString("source", ""),
                        timestampMs = o.optLong("ts", 0L)
                    )
                }.getOrNull()
            }
            facts.forEach { db.personFactDao().insert(it) }
            totalFacts += facts.size
        }
        Log.d(TAG, "Migrated $totalFacts person facts for ${names.size} people")
    }

    private fun migratePersonMentions(db: AegisDatabase, memory: EncryptedMemory) {
        val indexRaw = memory.getRaw("pf_index") ?: return
        val names = runCatching {
            val arr = JSONArray(indexRaw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())

        for (name in names) {
            val slug = name.lowercase().replace(Regex("\\s+"), "_").take(30)
            val mentionsRaw = memory.getRaw("pm_$slug") ?: continue
            val obj = runCatching { JSONObject(mentionsRaw) }.getOrNull() ?: continue

            val personId = db.personDao().idForName(name) ?: run {
                val id = db.personDao().insertIfAbsent(PersonEntity(name = name))
                if (id > 0L) id else db.personDao().idForName(name) ?: continue
            }

            val builtFlag = memory.getRaw("pm_${slug}_built") == "1"

            var totalMentions = 0
            obj.keys().forEach { key ->
                if (key.startsWith("_")) return@forEach
                val count = obj.optInt(key, 0)
                if (count > 0) {
                    repeat(count) { db.personMentionDao().incrementOrInsert(personId, key) }
                    totalMentions += count
                }
            }

            val sourceCount = db.personMentionDao().sourceCount(personId)
            val total       = db.personMentionDao().totalMentions(personId)
            val score       = (sourceCount.toFloat() / 6f).coerceIn(0f, 1f) * 0.6f +
                              (total.toFloat() / 50f).coerceIn(0f, 1f) * 0.4f
            val lastSeen    = obj.optLong("_last_seen", 0L)

            db.personDao().updateStats(personId, sourceCount, total, score, lastSeen)
            if (builtFlag) db.personDao().markProfileBuilt(name)
        }
        Log.d(TAG, "Migrated mention data for ${names.size} people")
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<LegacyMigrationWorker>().build()
            )
        }
    }
}
