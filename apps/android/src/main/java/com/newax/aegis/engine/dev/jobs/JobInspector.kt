package com.newax.aegis.engine.dev.jobs

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import com.newax.aegis.engine.dev.log.NewaxLogger
import java.util.concurrent.CopyOnWriteArrayList

data class ScheduledJobInfo(
    val jobId: Int,
    val service: String,
    val isPeriodic: Boolean,
    val periodMs: Long,
    val flexMs: Long,
    val requiresNetwork: Boolean,
    val requiresCharging: Boolean,
    val requiresDeviceIdle: Boolean,
    val requiresStorageNotLow: Boolean,
    val requiresBatteryNotLow: Boolean,
    val isPersisted: Boolean,
    val minLatencyMs: Long,
    val maxExecutionDelayMs: Long,
    val extras: String
)

data class WorkManagerJobInfo(
    val id: String,
    val state: String,
    val tags: List<String>,
    val runAttemptCount: Int,
    val progress: String
)

data class NewaxJobRecord(
    val label: String,
    val resourceClass: String,
    val priority: String,
    val startMs: Long,
    val endMs: Long?,
    val success: Boolean?,
    val durationMs: Long
)

object JobInspector {

    private val jobHistory = CopyOnWriteArrayList<NewaxJobRecord>()
    private const val MAX_HISTORY = 200

    fun scheduledJobs(context: Context): List<ScheduledJobInfo> {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        return js.allPendingJobs.map { job ->
            ScheduledJobInfo(
                jobId = job.id,
                service = job.service.className,
                isPeriodic = job.isPeriodic,
                periodMs = job.intervalMillis,
                flexMs = job.flexMillis,
                requiresNetwork = job.networkType != JobInfo.NETWORK_TYPE_NONE,
                requiresCharging = job.isRequireCharging,
                requiresDeviceIdle = job.isRequireDeviceIdle,
                requiresStorageNotLow = if (android.os.Build.VERSION.SDK_INT >= 26) job.isRequireStorageNotLow else false,
                requiresBatteryNotLow = if (android.os.Build.VERSION.SDK_INT >= 26) job.isRequireBatteryNotLow else false,
                isPersisted = job.isPersisted,
                minLatencyMs = job.minLatencyMillis,
                maxExecutionDelayMs = job.maxExecutionDelayMillis,
                extras = job.extras.toString()
            )
        }
    }

    fun workManagerJobs(context: Context): List<WorkManagerJobInfo> = try {
        val wm = androidx.work.WorkManager.getInstance(context)
        val infos = wm.getWorkInfosByTag("aegis").get()
        infos.map { info ->
            WorkManagerJobInfo(
                id = info.id.toString(),
                state = info.state.name,
                tags = info.tags.toList(),
                runAttemptCount = info.runAttemptCount,
                progress = info.progress.toString()
            )
        }
    } catch (_: Exception) { emptyList() }

    fun recordJobStart(label: String, resourceClass: String, priority: String): String {
        val id = "${label}_${System.currentTimeMillis()}"
        jobHistory.add(NewaxJobRecord(label, resourceClass, priority, System.currentTimeMillis(), null, null, 0L))
        if (jobHistory.size > MAX_HISTORY) jobHistory.removeAt(0)
        return id
    }

    fun recordJobEnd(label: String, success: Boolean) {
        val idx = jobHistory.indexOfLast { it.label == label && it.endMs == null }
        if (idx < 0) return
        val job = jobHistory[idx]
        jobHistory[idx] = job.copy(endMs = System.currentTimeMillis(), success = success, durationMs = System.currentTimeMillis() - job.startMs)
    }

    fun recentJobs(n: Int = 50): List<NewaxJobRecord> = jobHistory.takeLast(n)
    fun failedJobs(): List<NewaxJobRecord> = jobHistory.filter { it.success == false }
    fun runningJobs(): List<NewaxJobRecord> = jobHistory.filter { it.endMs == null }

    fun report(context: Context): String = buildString {
        val scheduled = scheduledJobs(context)
        append("JobScheduler: ${scheduled.size} pending jobs\n")
        scheduled.forEach { j -> append("  [${j.jobId}] ${j.service.substringAfterLast('.')} periodic=${j.isPeriodic}\n") }
        append("Newax job history: ${jobHistory.size} (${runningJobs().size} running, ${failedJobs().size} failed)\n")
        val wm = workManagerJobs(context)
        if (wm.isNotEmpty()) {
            append("WorkManager: ${wm.size} jobs\n")
            wm.forEach { w -> append("  [${w.id.take(8)}] ${w.state} tags=${w.tags}\n") }
        }
    }
}
