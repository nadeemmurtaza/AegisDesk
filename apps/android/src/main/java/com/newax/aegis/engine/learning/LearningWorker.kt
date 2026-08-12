package com.newax.aegis.engine.learning

import android.content.Context
import android.util.Log
import androidx.work.*
import com.newax.aegis.engine.resource.NewaxJob
import com.newax.aegis.engine.resource.JobPriority
import com.newax.aegis.engine.resource.ResourceClass
import com.newax.aegis.engine.resource.ResourceGovernor
import com.newax.aegis.memory.EncryptedMemory
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that executes one scan batch per run.
 *
 * Scheduled at [INTERVAL_MINUTES] intervals with battery-not-low constraint.
 * Each run processes one source (contacts/SMS/call logs/gallery/downloads)
 * in a small batch, then advances the source pointer for the next run.
 * This keeps CPU and IO pressure very low — no continuous background work.
 */
class LearningWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        if (ResourceGovernor.isCriticalRunning()) {
            Log.d(TAG, "LLM active — skipping batch, will retry")
            return Result.retry()
        }
        return try {
            var draftsCreated = 0
            runBlocking {
                ResourceGovernor.submit(NewaxJob(
                    id            = ResourceGovernor.newId(),
                    label         = "learning-batch",
                    resourceClass = ResourceClass.HEAVY,
                    priority      = JobPriority.P3_INDEXING,
                    cancellable   = true,
                    checkpointable = true
                ) {
                    val memory = EncryptedMemory(applicationContext)
                    draftsCreated = BackgroundLearner.runNextBatch(applicationContext, memory)
                })
            }
            Log.d(TAG, "Batch done: $draftsCreated new draft(s)")
            Result.success(workDataOf(OUTPUT_KEY to draftsCreated))
        } catch (e: Exception) {
            Log.w(TAG, "Worker error: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NewaxLearner"
        private const val WORK_NAME = "aegis_background_learner"
        private const val OUTPUT_KEY = "drafts_created"
        const val INTERVAL_MINUTES = 20L   // one source batch every 20 min

        /**
         * Start the periodic learner. Safe to call multiple times (ExistingPeriodicWorkPolicy.UPDATE
         * keeps the schedule live and resets the interval if the config changed).
         * Requires battery-not-low to prevent drain.
         */
        fun schedule(context: Context, intervalMinutes: Long = -1L) {
            ScanProgress.init(context)
            ScanProgress.setEnabled(true)
            val interval = if (intervalMinutes > 0) {
                ScanProgress.setIntervalMinutes(intervalMinutes)
                intervalMinutes
            } else {
                ScanProgress.getIntervalMinutes()
            }

            val request = PeriodicWorkRequestBuilder<LearningWorker>(interval, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(2, TimeUnit.MINUTES)   // don't start immediately on enable
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Scheduled: every ${interval}min")
        }

        /** Stop the periodic learner. Does not clear existing drafts or progress. */
        fun cancel(context: Context) {
            ScanProgress.init(context)
            ScanProgress.setEnabled(false)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled")
        }

        /** Force one immediate batch run outside the periodic schedule (e.g. on user request). */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<LearningWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun isScheduled(context: Context): Boolean {
            return try {
                val info = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(WORK_NAME).get()
                info.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            } catch (_: Exception) { false }
        }
    }
}
