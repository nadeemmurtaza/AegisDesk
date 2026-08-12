package com.newax.aegis

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * The automatic sync loop on Android (docs/SYNC_DESIGN.md §4.2): a periodic
 * WorkManager job that runs one full [SyncEngine.runCycle] — LAN transport +
 * relay phase against the Room-backed journal. Thin wrapper now: the cycle
 * itself lives in [SyncEngine], shared with the continuous
 * [SyncForegroundService] (the transport that actually stays up between
 * worker windows).
 *
 * No-op until at least one peer is paired (the handshake rejects unpaired
 * devices), so the default-on setting is harmless before pairing.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!SyncRuntime.enabled()) return Result.success()
        return try {
            SyncEngine.runCycle()
            Result.success()
        } catch (e: Exception) {
            SyncRuntime.recordStatus("Sync error: ${e.message ?: e.javaClass.simpleName}")
            Result.retry()
        }
    }
}
