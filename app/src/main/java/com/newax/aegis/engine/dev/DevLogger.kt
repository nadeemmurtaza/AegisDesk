package com.newax.aegis.engine.dev

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DevLogger {

    enum class Level(val priority: Int, val short: String) {
        VERBOSE(Log.VERBOSE, "V"),
        DEBUG(Log.DEBUG, "D"),
        INFO(Log.INFO, "I"),
        WARN(Log.WARN, "W"),
        ERROR(Log.ERROR, "E")
    }

    data class Entry(
        val id: Long,
        val timestampMs: Long,
        val tag: String,
        val level: Level,
        val message: String
    ) {
        val formatted: String get() =
            "${SDF.format(Date(timestampMs))} ${level.short}/$tag: $message"
    }

    private const val MAX = 3000
    private val SDF = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private var seq = 0L

    @Synchronized
    fun log(tag: String, level: Level, message: String) {
        Log.println(level.priority, tag, message)
        val e = Entry(seq++, System.currentTimeMillis(), tag, level, message)
        val cur = _entries.value
        _entries.value = if (cur.size >= MAX) cur.drop(cur.size - MAX + 1) + e else cur + e
    }

    fun v(tag: String, msg: String) = log(tag, Level.VERBOSE, msg)
    fun d(tag: String, msg: String) = log(tag, Level.DEBUG, msg)
    fun i(tag: String, msg: String) = log(tag, Level.INFO, msg)
    fun w(tag: String, msg: String) = log(tag, Level.WARN, msg)
    fun e(tag: String, msg: String) = log(tag, Level.ERROR, msg)

    fun clear() { _entries.value = emptyList() }

    fun export(): String = _entries.value.joinToString("\n") { it.formatted }
}
