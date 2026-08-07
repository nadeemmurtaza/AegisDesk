package com.newax.aegis.engine

import org.json.JSONArray
import org.json.JSONObject

data class LogEntry(
    val timestamp: Long,
    val contact: String,
    val message: String,
    val direction: String = "IN",   // "IN" | "OUT"
    val source: String = ""         // app name: "WhatsApp", "Gmail", etc.
) {
    // Backward-compat alias used by existing callers
    val summary: String get() = message
}

object CommunicationLog {
    private const val MAX_ENTRIES = 500
    private val lock = Any()
    private val logs = ArrayDeque<LogEntry>()

    // --- Write ---

    /** Add a new log entry from any source. */
    fun addLog(
        contact: String,
        message: String,
        direction: String = "IN",
        source: String = ""
    ) = synchronized(lock) {
        if (logs.size >= MAX_ENTRIES) logs.removeFirst()
        logs.addLast(LogEntry(System.currentTimeMillis(), contact, message, direction, source))
    }

    /** Backward-compat alias for addLog(). */
    fun logInteraction(contact: String, summary: String) =
        addLog(contact, summary, "IN", "")

    // --- Read ---

    /** Returns the most recent [limit] logs, newest first. */
    fun getLogs(limit: Int = 20): List<LogEntry> = synchronized(lock) {
        logs.reversed().take(limit)
    }

    /** Returns logs for a specific contact, newest first, up to [limit]. */
    fun getLogsForContact(contact: String, limit: Int = 50): List<LogEntry> = synchronized(lock) {
        logs.filter { it.contact.equals(contact, ignoreCase = true) }
            .reversed().take(limit)
    }

    /** Returns logs within [fromMs, toMs] inclusive, newest first. */
    fun getLogsInRange(fromMs: Long, toMs: Long): List<LogEntry> = synchronized(lock) {
        logs.filter { it.timestamp in fromMs..toMs }.sortedByDescending { it.timestamp }
    }

    fun getAllLogs(): List<LogEntry> = synchronized(lock) { logs.toList() }

    // --- Delete ---

    /** Deletes all logs for a contact. Returns count deleted. */
    fun deleteLogsForContact(contact: String): Int = synchronized(lock) {
        val before = logs.size
        logs.removeAll { it.contact.equals(contact, ignoreCase = true) }
        before - logs.size
    }

    /** Deletes a single log by exact timestamp. */
    fun deleteLog(timestamp: Long): Boolean = synchronized(lock) {
        logs.removeAll { it.timestamp == timestamp }
    }

    // --- Persistence ---

    fun serialize(): String = synchronized(lock) {
        val array = JSONArray()
        for (log in logs) {
            array.put(JSONObject().apply {
                put("timestamp", log.timestamp)
                put("contact",   log.contact)
                put("message",   log.message)
                put("direction", log.direction)
                put("source",    log.source)
            })
        }
        array.toString()
    }

    fun load(jsonStr: String) = synchronized(lock) {
        logs.clear()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                // "message" field; fall back to legacy "summary" key
                val msg = if (obj.has("message")) obj.getString("message")
                          else obj.optString("summary", "")
                logs.addLast(LogEntry(
                    timestamp = obj.getLong("timestamp"),
                    contact   = obj.getString("contact"),
                    message   = msg,
                    direction = obj.optString("direction", "IN"),
                    source    = obj.optString("source", "")
                ))
            }
            while (logs.size > MAX_ENTRIES) logs.removeFirst()
        } catch (_: Exception) {}
    }
}
