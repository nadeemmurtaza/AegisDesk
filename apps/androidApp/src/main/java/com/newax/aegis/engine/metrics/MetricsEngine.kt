package com.newax.aegis.engine.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ModuleMetrics(
    val module: String,
    val callCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val totalLatencyMs: Long,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val lastCalledMs: Long
) {
    val avgLatencyMs: Long get() = if (callCount == 0L) 0L else totalLatencyMs / callCount
    val successRate: Float get() = if (callCount == 0L) 1f else successCount.toFloat() / callCount
    val p99LatencyMs: Long get() = maxLatencyMs
}

class LatencyTracker(private val maxSamples: Int = 100) {
    private val samples = ArrayDeque<Long>(maxSamples)
    private val lock = Any()

    fun record(ms: Long) = synchronized(lock) {
        if (samples.size >= maxSamples) samples.removeFirst()
        samples.addLast(ms)
    }

    fun percentile(p: Int): Long = synchronized(lock) {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val idx = (p / 100.0 * sorted.size).toInt().coerceAtMost(sorted.size - 1)
        sorted[idx]
    }

    fun avg(): Long = synchronized(lock) {
        if (samples.isEmpty()) 0L else samples.sum() / samples.size
    }
}

object MetricsEngine {

    private val metrics = ConcurrentHashMap<String, MutableModuleMetrics>()
    private val latencyTrackers = ConcurrentHashMap<String, LatencyTracker>()

    private data class MutableModuleMetrics(
        val module: String,
        val calls: AtomicLong = AtomicLong(0),
        val successes: AtomicLong = AtomicLong(0),
        val failures: AtomicLong = AtomicLong(0),
        val totalMs: AtomicLong = AtomicLong(0),
        @Volatile var minMs: Long = Long.MAX_VALUE,
        @Volatile var maxMs: Long = 0L,
        @Volatile var lastCalledMs: Long = 0L
    )

    fun record(module: String, success: Boolean, latencyMs: Long) {
        val m = metrics.getOrPut(module) { MutableModuleMetrics(module) }
        m.calls.incrementAndGet()
        if (success) m.successes.incrementAndGet() else m.failures.incrementAndGet()
        m.totalMs.addAndGet(latencyMs)
        if (latencyMs < m.minMs) m.minMs = latencyMs
        if (latencyMs > m.maxMs) m.maxMs = latencyMs
        m.lastCalledMs = System.currentTimeMillis()
        latencyTrackers.getOrPut(module) { LatencyTracker() }.record(latencyMs)
    }

    fun <T> measure(module: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            record(module, true, System.currentTimeMillis() - start)
            result
        } catch (e: Exception) {
            record(module, false, System.currentTimeMillis() - start)
            throw e
        }
    }

    suspend fun <T> measureSuspend(module: String, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            record(module, true, System.currentTimeMillis() - start)
            result
        } catch (e: Exception) {
            record(module, false, System.currentTimeMillis() - start)
            throw e
        }
    }

    fun get(module: String): ModuleMetrics? {
        val m = metrics[module] ?: return null
        return ModuleMetrics(
            module = m.module,
            callCount = m.calls.get(),
            successCount = m.successes.get(),
            failureCount = m.failures.get(),
            totalLatencyMs = m.totalMs.get(),
            minLatencyMs = if (m.minMs == Long.MAX_VALUE) 0L else m.minMs,
            maxLatencyMs = m.maxMs,
            lastCalledMs = m.lastCalledMs
        )
    }

    fun all(): List<ModuleMetrics> = metrics.keys.mapNotNull { get(it) }
        .sortedByDescending { it.callCount }

    fun topSlow(n: Int = 5): List<ModuleMetrics> =
        all().sortedByDescending { it.avgLatencyMs }.take(n)

    fun topFailing(n: Int = 5): List<ModuleMetrics> =
        all().filter { it.callCount > 0 }
            .sortedBy { it.successRate }
            .take(n)

    fun p99(module: String): Long =
        latencyTrackers[module]?.percentile(99) ?: 0L

    fun reset(module: String) {
        metrics.remove(module)
        latencyTrackers.remove(module)
    }

    fun resetAll() {
        metrics.clear()
        latencyTrackers.clear()
    }

    fun summary(): String = buildString {
        val all = all()
        if (all.isEmpty()) { append("No metrics recorded."); return@buildString }
        append("Module Metrics Summary:\n")
        all.take(10).forEach { m ->
            append("  ${m.module}: ${m.callCount} calls, ${(m.successRate * 100).toInt()}% success, avg ${m.avgLatencyMs}ms\n")
        }
    }
}
