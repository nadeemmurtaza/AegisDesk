package com.newax.aegis.engine.resource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object ResourceGovernor {

    // ── Pressure ──────────────────────────────────────────────────────────────

    val pressureLevel = AtomicInteger(0)   // 0–5

    fun onMemoryPressure(level: Int) {
        pressureLevel.set(level.coerceIn(0, 5))
        when {
            level >= 5 -> cancelByClass(ResourceClass.HEAVY)
            level >= 4 -> pauseBackground()
            level >= 2 -> clearHeavyQueue()
        }
    }

    // ── Internal state ────────────────────────────────────────────────────────

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex       = Mutex()
    private val queue       = PriorityQueue<AegisJob>(compareBy { it.priority.level })
    private var heavyJob:   Job? = null
    private var heavyLabel: String? = null
    private var criticalRunning = false

    // ── Submit ────────────────────────────────────────────────────────────────

    suspend fun submit(job: AegisJob): String {
        return mutex.withLock {
            when (job.resourceClass) {
                ResourceClass.TINY  -> { launchTiny(job); job.id }
                ResourceClass.LIGHT -> { launchLight(job); job.id }
                ResourceClass.HEAVY, ResourceClass.CRITICAL -> {
                    queue.offer(job)
                    drainQueue()
                    job.id
                }
            }
        }
    }

    fun cancel(jobId: String) {
        scope.launch {
            mutex.withLock {
                queue.removeIf { it.id == jobId }
                if (heavyLabel == jobId) {
                    heavyJob?.cancel()
                    heavyJob = null
                    heavyLabel = null
                    criticalRunning = false
                    drainQueue()
                }
            }
        }
    }

    // ── Preempt: P0 user command cancels running heavy job ───────────────────

    suspend fun preemptForUser(job: AegisJob): String {
        mutex.withLock {
            if (heavyJob?.isActive == true && heavyLabel != null) {
                heavyJob!!.cancel()
                heavyJob = null
                criticalRunning = false
            }
        }
        return submit(job)
    }

    // ── Internal launchers ────────────────────────────────────────────────────

    private fun launchTiny(job: AegisJob) {
        scope.launch(Dispatchers.IO) { runSafe(job) }
    }

    private fun launchLight(job: AegisJob) {
        if (pressureLevel.get() >= 4 && job.priority.level > JobPriority.P1_ACTIVE_SEARCH.level) return
        scope.launch(Dispatchers.IO) { runSafe(job) }
    }

    private fun drainQueue() {
        if (heavyJob?.isActive == true) return
        val next = queue.poll() ?: return
        val isC  = next.resourceClass == ResourceClass.CRITICAL
        if (isC) criticalRunning = true
        heavyLabel = next.id
        heavyJob   = scope.launch(Dispatchers.IO) {
            try {
                runSafe(next)
            } finally {
                mutex.withLock {
                    heavyJob = null
                    heavyLabel = null
                    if (isC) criticalRunning = false
                    drainQueue()
                }
            }
        }
    }

    private suspend fun runSafe(job: AegisJob) {
        try { job.block() } catch (_: CancellationException) { } catch (_: Exception) { }
    }

    private fun cancelByClass(cls: ResourceClass) {
        scope.launch {
            mutex.withLock {
                queue.removeIf { it.resourceClass == cls }
                if (heavyLabel != null) {
                    heavyJob?.cancel()
                    heavyJob = null
                    heavyLabel = null
                    criticalRunning = false
                }
            }
        }
    }

    private fun pauseBackground() {
        scope.launch {
            mutex.withLock {
                queue.removeIf { it.priority.level >= JobPriority.P3_INDEXING.level }
            }
        }
    }

    private fun clearHeavyQueue() {
        scope.launch {
            mutex.withLock {
                queue.removeIf { it.resourceClass == ResourceClass.HEAVY }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun isHeavyRunning()    = heavyJob?.isActive == true
    fun isCriticalRunning() = criticalRunning
    fun queueDepth()        = queue.size

    fun newId() = UUID.randomUUID().toString().take(8)

    fun fire(
        label: String,
        resourceClass: ResourceClass = ResourceClass.HEAVY,
        priority: JobPriority = JobPriority.P4_EMBEDDINGS,
        ramBudgetMb: Int = 50,
        block: suspend () -> Unit
    ) {
        scope.launch {
            submit(AegisJob(
                id            = newId(),
                label         = label,
                resourceClass = resourceClass,
                priority      = priority,
                ramBudgetMb   = ramBudgetMb,
                cancellable   = true,
                block         = block
            ))
        }
    }
}
