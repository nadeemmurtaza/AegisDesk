package com.newax.aegis.engine.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.files.FileIndexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class IntelligenceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return@withContext Result.retry()
        try {
            FileIndexer.runTextExtraction(applicationContext, db, 50)
            FileIndexer.runEntityExtraction(db, 50)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "AegisIntelligenceWorker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<IntelligenceWorker>(3, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
            )
        }
    }
}
