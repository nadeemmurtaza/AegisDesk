package com.newax.aegis.engine.dev.log

import java.util.concurrent.CopyOnWriteArrayList

data class CrashRecord(
    val id: Long,
    val timestampMs: Long,
    val type: String,
    val message: String,
    val stackTrace: String,
    val thread: String,
    val breadcrumbs: List<String>,
    val metadata: Map<String, String>
)

data class AnrRecord(
    val id: Long,
    val timestampMs: Long,
    val thread: String,
    val blockedMs: Long,
    val stackTrace: String
)

object CrashReporter {

    private const val MAX_CRASHES = 50
    private const val MAX_BREADCRUMBS = 30
    private val crashes = CopyOnWriteArrayList<CrashRecord>()
    private val anrs = CopyOnWriteArrayList<AnrRecord>()
    private val breadcrumbs = CopyOnWriteArrayList<String>()
    private var crashIdCounter = 0L
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(throwable, thread.name)
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    fun breadcrumb(message: String) {
        val ts = System.currentTimeMillis()
        breadcrumbs.add("$ts: $message")
        if (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeAt(0)
    }

    fun record(throwable: Throwable, thread: String = Thread.currentThread().name, metadata: Map<String, String> = emptyMap()) {
        val record = CrashRecord(
            id = ++crashIdCounter,
            timestampMs = System.currentTimeMillis(),
            type = throwable::class.java.simpleName,
            message = throwable.message ?: "(no message)",
            stackTrace = throwable.stackTrace.take(20).joinToString("\n") { "  at $it" },
            thread = thread,
            breadcrumbs = breadcrumbs.toList(),
            metadata = metadata
        )
        crashes.add(record)
        if (crashes.size > MAX_CRASHES) crashes.removeAt(0)
        NewaxLogger.e("CrashReporter", "CRASH [${record.type}]: ${record.message}")
    }

    fun recordAnr(thread: String, blockedMs: Long) {
        val stack = Thread.getAllStackTraces()
            .filter { (t, _) -> t.name == thread || t.name == "main" }
            .flatMap { (t, frames) -> listOf("Thread: ${t.name}") + frames.take(10).map { "  at $it" } }
            .joinToString("\n")
        anrs.add(AnrRecord(++crashIdCounter, System.currentTimeMillis(), thread, blockedMs, stack))
        if (anrs.size > MAX_CRASHES) anrs.removeAt(0)
    }

    fun recentCrashes(n: Int = 10): List<CrashRecord> = crashes.takeLast(n)
    fun recentAnrs(n: Int = 10): List<AnrRecord> = anrs.takeLast(n)
    fun currentBreadcrumbs(): List<String> = breadcrumbs.toList()
    fun clear() { crashes.clear(); anrs.clear(); breadcrumbs.clear() }

    fun exportBundle(): String = buildString {
        append("=== CRASH REPORT BUNDLE ===\n")
        append("Generated: ${System.currentTimeMillis()}\n\n")
        if (crashes.isEmpty()) {
            append("No crashes recorded.\n")
        } else {
            crashes.forEach { c ->
                append("--- CRASH #${c.id} ---\n")
                append("Time: ${c.timestampMs}\nType: ${c.type}\nMsg: ${c.message}\nThread: ${c.thread}\n")
                append("Stack:\n${c.stackTrace}\n")
                append("Breadcrumbs:\n${c.breadcrumbs.joinToString("\n")}\n\n")
            }
        }
        if (anrs.isNotEmpty()) {
            append("=== ANRs ===\n")
            anrs.forEach { a ->
                append("ANR #${a.id} thread=${a.thread} blocked=${a.blockedMs}ms\n${a.stackTrace}\n\n")
            }
        }
    }
}
