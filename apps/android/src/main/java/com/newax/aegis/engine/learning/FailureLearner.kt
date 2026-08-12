package com.newax.aegis.engine.learning

import com.newax.aegis.engine.bus.NewaxEvent
import com.newax.aegis.engine.bus.NewaxEventBus
import java.util.concurrent.ConcurrentHashMap

data class FailureRecord(
    val id: String,
    val module: String,
    val operationId: String,
    val reason: String,
    val context: Map<String, String> = emptyMap(),
    val retryCount: Int = 0,
    val resolved: Boolean = false,
    val patch: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class FailurePattern(
    val module: String,
    val operationId: String,
    val occurrences: Int,
    val lastSeenMs: Long,
    val commonReasons: List<String>,
    val suggestedPatch: String?
)

object FailureLearner {

    private const val MAX_RECORDS = 500
    private const val PATTERN_MIN_OCCURRENCES = 3
    private const val PATTERN_WINDOW_MS = 24 * 3600 * 1000L

    private val records = mutableListOf<FailureRecord>()
    private val lock = Any()

    fun record(
        module: String,
        operationId: String,
        reason: String,
        context: Map<String, String> = emptyMap(),
        retryCount: Int = 0
    ): FailureRecord {
        val record = FailureRecord(
            id = "${module}_${operationId}_${System.currentTimeMillis()}",
            module = module,
            operationId = operationId,
            reason = reason,
            context = context,
            retryCount = retryCount
        )
        synchronized(lock) {
            if (records.size >= MAX_RECORDS) records.removeAt(0)
            records.add(record)
        }
        NewaxEventBus.emit(NewaxEvent.FailureRecorded(module, operationId, reason))
        return record
    }

    fun resolve(id: String, patch: String? = null) = synchronized(lock) {
        val idx = records.indexOfFirst { it.id == id }
        if (idx >= 0) {
            records[idx] = records[idx].copy(resolved = true, patch = patch)
        }
    }

    fun detectPatterns(): List<FailurePattern> {
        val now = System.currentTimeMillis()
        val recent = synchronized(lock) {
            records.filter { !it.resolved && now - it.timestampMs < PATTERN_WINDOW_MS }
        }

        return recent
            .groupBy { "${it.module}:${it.operationId}" }
            .filter { it.value.size >= PATTERN_MIN_OCCURRENCES }
            .map { (key, failures) ->
                val (module, op) = key.split(":", limit = 2)
                val reasons = failures.map { it.reason }
                    .groupingBy { it }.eachCount()
                    .toList().sortedByDescending { it.second }
                    .take(3).map { it.first }
                FailurePattern(
                    module = module,
                    operationId = op,
                    occurrences = failures.size,
                    lastSeenMs = failures.maxOf { it.timestampMs },
                    commonReasons = reasons,
                    suggestedPatch = suggestPatch(module, op, reasons)
                )
            }
    }

    fun recentFailures(module: String? = null, limit: Int = 50): List<FailureRecord> =
        synchronized(lock) {
            val base = if (module != null) records.filter { it.module == module } else records.toList()
            base.sortedByDescending { it.timestampMs }.take(limit)
        }

    fun failureRate(module: String, windowMs: Long = 3600_000L): Float {
        val now = System.currentTimeMillis()
        val recent = synchronized(lock) {
            records.filter { it.module == module && now - it.timestampMs < windowMs }
        }
        return recent.size / (windowMs / 60_000f)
    }

    fun topFailingModules(n: Int = 5): List<Pair<String, Int>> {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            records.filter { now - it.timestampMs < PATTERN_WINDOW_MS && !it.resolved }
        }
            .groupingBy { it.module }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(n)
    }

    fun clear() = synchronized(lock) { records.clear() }

    private fun suggestPatch(module: String, op: String, reasons: List<String>): String? {
        val reason = reasons.firstOrNull()?.lowercase() ?: return null
        return when {
            reason.contains("timeout") -> "Increase timeout or add retry with backoff"
            reason.contains("null") -> "Add null-check before $op in $module"
            reason.contains("not found") -> "Verify resource exists before $op in $module"
            reason.contains("permission") -> "Request required permission before $op"
            reason.contains("network") -> "Add offline fallback for $op in $module"
            else -> null
        }
    }
}
