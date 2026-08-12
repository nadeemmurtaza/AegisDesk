package com.newax.aegis.engine.learning

import android.content.Context
import android.util.Log
import androidx.work.*
import com.newax.aegis.agents.LearningEngine
import java.util.concurrent.TimeUnit

/**
 * The Continuous Fuzzing Engine's idle-time worker
 * (skill.sys.background_fuzzer — docs/AGENTS_DESIGN.md §evolution). Runs
 * [LearningEngine.fuzzPass] (propose alternative methods against the
 * observed benchmark, staged behind the user gate) plus the RLAIF reflection
 * pass ([LearningEngine.consumeSignals]) when the device is idle AND
 * charging — the exact "when the system is idle" window the design specifies.
 * It never deploys anything; every candidate pauses at the Updates screen
 * until a human approves it.
 */
class EvolutionWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        return try {
            val staged = LearningEngine.fuzzPass()
            val consumed = LearningEngine.consumeSignals()
            Log.d(TAG, "Fuzz pass done: $staged candidate(s) staged, $consumed signal(s) reflected")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Fuzz pass error: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NewaxEvolution"
        private const val WORK_NAME = "aegis_evolution_fuzzer"
        private const val INTERVAL_HOURS = 6L

        /** Periodic, idle + charging only (battery-safe by construction). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EvolutionWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresDeviceIdle(true)
                        .setRequiresCharging(true)
                        .build()
                )
                .setInitialDelay(1, TimeUnit.HOURS) // not immediately after app start
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
