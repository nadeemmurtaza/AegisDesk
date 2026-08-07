package com.newax.aegis.engine

import android.util.Log

object HabitTracker {
    private const val MAX_LOG_SIZE = 200
    private const val AI_TRIGGER_INTERVAL = 25
    private const val AI_COOLDOWN_MS = 5 * 60 * 1000L

    private val lock = Any()
    private val appLogs = ArrayDeque<String>()

    /** pkg -> list of hour-of-day open times (0-23) */
    private val timeBuckets = mutableMapOf<String, MutableList<Int>>()
    private var lastAiTrigger = 0L
    private var opensSinceLastTrigger = 0

    fun logAppOpen(packageName: String) {
        val now = java.util.Date()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(now)
        val entry = "$time - Opened $packageName"

        synchronized(lock) {
            if (appLogs.size >= MAX_LOG_SIZE) appLogs.removeFirst()
            appLogs.addLast(entry)
            timeBuckets.getOrPut(packageName) { mutableListOf() }.add(hour)
            opensSinceLastTrigger++
        }

        if (shouldTriggerAi()) triggerAiAnalysis()
    }

    private fun shouldTriggerAi(): Boolean {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            opensSinceLastTrigger >= AI_TRIGGER_INTERVAL && now - lastAiTrigger >= AI_COOLDOWN_MS
        }
    }

    private fun triggerAiAnalysis() {
        val (snapshot, patterns) = synchronized(lock) {
            lastAiTrigger = System.currentTimeMillis()
            opensSinceLastTrigger = 0
            val snap = appLogs.takeLast(AI_TRIGGER_INTERVAL).toList()
            val pats = detectLocalPatterns()
            snap to pats
        }

        val prompt = buildString {
            append("User App Habits (last ${snapshot.size} opens):\n")
            append(snapshot.joinToString("\n"))
            if (patterns.isNotEmpty()) {
                append("\n\nLocally-detected patterns:\n")
                append(patterns.joinToString("\n"))
            }
            append("\n\nIf a consistent time-of-day habit is confirmed, propose: update memory habits <fact>. Otherwise output \"No pattern\".")
        }
        TriggerEngine.triggerEvents.tryEmit(prompt)
    }

    /**
     * Local pattern detection without AI: finds apps opened ≥3 times
     * where 70%+ of opens fall within the same 3-hour window.
     */
    private fun detectLocalPatterns(): List<String> {
        val result = mutableListOf<String>()
        for ((pkg, hours) in timeBuckets) {
            if (hours.size < 3) continue
            for (startHour in 0..21) {
                val inWindow = hours.count { it in startHour..(startHour + 2) }
                if (inWindow.toDouble() / hours.size >= 0.7) {
                    result.add("$pkg opened ${inWindow}/${hours.size} times in ${startHour}:00-${startHour + 2}:59")
                    break
                }
            }
        }
        return result
    }
}
