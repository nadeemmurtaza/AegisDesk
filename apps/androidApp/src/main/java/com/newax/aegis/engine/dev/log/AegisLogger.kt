package com.newax.aegis.engine.dev.log

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel(val priority: Int) { VERBOSE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4), ASSERT(5) }

data class LogEntry(
    val id: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val taskId: String?,
    val module: String?,
    val timestampMs: Long,
    val threadName: String
)

object AegisLogger {

    private const val RING_SIZE = 2000
    private val counter = AtomicLong(0)
    private val ring = CopyOnWriteArrayList<LogEntry>()
    private var minLevel: LogLevel = LogLevel.DEBUG
    private var logcatMirror: Boolean = true

    fun setMinLevel(level: LogLevel) { minLevel = level }
    fun setLogcatMirror(enabled: Boolean) { logcatMirror = enabled }

    fun v(tag: String, msg: String, taskId: String? = null, module: String? = null) =
        log(LogLevel.VERBOSE, tag, msg, taskId, module)
    fun d(tag: String, msg: String, taskId: String? = null, module: String? = null) =
        log(LogLevel.DEBUG, tag, msg, taskId, module)
    fun i(tag: String, msg: String, taskId: String? = null, module: String? = null) =
        log(LogLevel.INFO, tag, msg, taskId, module)
    fun w(tag: String, msg: String, taskId: String? = null, module: String? = null) =
        log(LogLevel.WARN, tag, msg, taskId, module)
    fun e(tag: String, msg: String, taskId: String? = null, module: String? = null) =
        log(LogLevel.ERROR, tag, msg, taskId, module)

    fun log(level: LogLevel, tag: String, message: String, taskId: String? = null, module: String? = null) {
        if (level.priority < minLevel.priority) return
        val entry = LogEntry(
            id = counter.incrementAndGet(),
            level = level,
            tag = tag,
            message = message,
            taskId = taskId,
            module = module,
            timestampMs = System.currentTimeMillis(),
            threadName = Thread.currentThread().name
        )
        ring.add(entry)
        if (ring.size > RING_SIZE) ring.removeAt(0)
        if (logcatMirror) {
            when (level) {
                LogLevel.VERBOSE -> Log.v(tag, message)
                LogLevel.DEBUG -> Log.d(tag, message)
                LogLevel.INFO -> Log.i(tag, message)
                LogLevel.WARN -> Log.w(tag, message)
                LogLevel.ERROR -> Log.e(tag, message)
                LogLevel.ASSERT -> Log.wtf(tag, message)
            }
        }
    }

    fun recent(n: Int = 100): List<LogEntry> = ring.takeLast(n)

    fun byLevel(level: LogLevel, n: Int = 100): List<LogEntry> =
        ring.filter { it.level == level }.takeLast(n)

    fun byTag(tag: String, n: Int = 100): List<LogEntry> =
        ring.filter { it.tag == tag }.takeLast(n)

    fun byModule(module: String, n: Int = 100): List<LogEntry> =
        ring.filter { it.module == module }.takeLast(n)

    fun byTask(taskId: String): List<LogEntry> =
        ring.filter { it.taskId == taskId }

    fun errors(n: Int = 50): List<LogEntry> =
        ring.filter { it.level.priority >= LogLevel.ERROR.priority }.takeLast(n)

    fun search(query: String, n: Int = 100): List<LogEntry> {
        val lower = query.lowercase()
        return ring.filter { it.message.lowercase().contains(lower) || it.tag.lowercase().contains(lower) }.takeLast(n)
    }

    fun clear() { ring.clear() }

    fun export(): String = buildString {
        ring.forEach { e ->
            append("[${e.level.name[0]}] ${e.tag}/${e.module ?: "-"} t=${e.taskId ?: "-"}: ${e.message}\n")
        }
    }

    fun stats(): Map<String, Int> = buildMap {
        LogLevel.values().forEach { level -> put(level.name, ring.count { it.level == level }) }
        put("TOTAL", ring.size)
    }
}
