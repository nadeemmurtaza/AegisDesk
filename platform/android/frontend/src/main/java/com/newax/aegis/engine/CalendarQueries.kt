package com.newax.aegis.engine

import android.content.Context
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CalendarQueries {

    data class CalEvent(val title: String, val startMs: Long) {
        fun formatted(pattern: String = "MMM dd, HH:mm"): String =
            "$title at ${SimpleDateFormat(pattern, Locale.getDefault()).format(Date(startMs))}"
    }

    fun query(context: Context, fromMs: Long, untilMs: Long, limit: Int = 10): List<CalEvent> {
        val results = mutableListOf<CalEvent>()
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(fromMs.toString(), untilMs.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { c ->
                val ti = c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val di = c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                var count = 0
                while (c.moveToNext() && count < limit) {
                    val title = c.getString(ti) ?: continue
                    results += CalEvent(title, c.getLong(di))
                    count++
                }
            }
        } catch (_: Exception) {}
        return results
    }
}
