package com.newax.aegis.engine.embedding

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.runBlocking
import com.newax.aegis.db.AegisDatabase
import java.util.concurrent.TimeUnit

/**
 * One-shot worker that embeds all person_facts rows not yet in the embeddings table.
 * Runs in batches of [BATCH_SIZE] to avoid blocking the DB for too long.
 * Re-schedules itself until all facts are indexed.
 */
class EmbeddingIndexWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    private val TAG        = "AegisEmbIdx"
    private val BATCH_SIZE = 50

    override fun doWork(): Result {
        if (!EmbeddingEngine.isReady()) {
            Log.d(TAG, "Engine not ready")
            return Result.success()
        }
        val db = try { AegisDatabase.get } catch (_: Exception) { return Result.retry() }

        val indexed   = runBlocking { db.embeddingDao().getSourceIds(VectorStore.TYPE_FACT) }.toSet()
        val allIds    = runBlocking { db.personFactDao().getAllIds() }
        val unindexed = allIds.filter { it.toString() !in indexed }

        if (unindexed.isEmpty()) {
            VectorStore.pruneOrphans(db)
            Log.d(TAG, "All ${allIds.size} facts indexed")
            return Result.success()
        }

        val batch = runBlocking { db.personFactDao().getByIds(unindexed.take(BATCH_SIZE)) }
        for (fact in batch) {
            val text = buildString {
                append(fact.fact)
                if (fact.category.isNotBlank()) append(" [${fact.category}]")
            }
            VectorStore.indexFact(db, fact.id, text)
        }
        Log.d(TAG, "Indexed ${batch.size} facts (${unindexed.size - batch.size} remaining)")

        if (unindexed.size > BATCH_SIZE) schedule(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "aegis_embedding_index"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<EmbeddingIndexWorker>()
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
                    .setInitialDelay(3, TimeUnit.SECONDS)
                    .build()
            )
        }

        fun isNeeded(db: AegisDatabase): Boolean = runBlocking {
            db.personFactDao().getAllIds().size > db.embeddingDao().count()
        }
    }
}
