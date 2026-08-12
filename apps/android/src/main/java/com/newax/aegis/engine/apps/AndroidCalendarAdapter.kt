package com.newax.aegis.engine.apps

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import java.util.TimeZone

class AndroidCalendarAdapter(private val context: Context) : CalendarAdapter {
    
    override fun getUpcomingEvents(days: Int): List<CalendarEvent> {
        val resolver: ContentResolver = context.contentResolver
        val events = mutableListOf<CalendarEvent>()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION
        )

        val now = System.currentTimeMillis()
        val future = now + (days * 24 * 60 * 60 * 1000L)

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(now.toString(), future.toString())

        val cursor = resolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        )

        cursor?.use {
            val idIdx = it.getColumnIndex(CalendarContract.Events._ID)
            val titleIdx = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startIdx = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIdx = it.getColumnIndex(CalendarContract.Events.DTEND)
            val descIdx = it.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val locIdx = it.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)

            while (it.moveToNext()) {
                events.add(
                    CalendarEvent(
                        id = it.getString(idIdx),
                        title = it.getString(titleIdx) ?: "",
                        startTime = it.getLong(startIdx),
                        endTime = it.getLong(endIdx),
                        description = it.getString(descIdx),
                        location = it.getString(locIdx)
                    )
                )
            }
        }
        return events
    }

    override fun createEvent(
        title: String,
        startTime: Long,
        endTime: Long,
        description: String?,
        location: String?
    ): Boolean {
        val resolver: ContentResolver = context.contentResolver
        
        // Find a primary calendar ID
        var calendarId: Long = -1
        val calCursor = resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.IS_PRIMARY} = 1",
            null,
            null
        )
        
        calCursor?.use {
            if (it.moveToFirst()) {
                val idIdx = it.getColumnIndex(CalendarContract.Calendars._ID)
                calendarId = it.getLong(idIdx)
            }
        }
        
        if (calendarId == -1L) {
            // fallback to first calendar if no primary
            val calCursor2 = resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                null,
                null,
                null
            )
            calCursor2?.use {
                if (it.moveToFirst()) {
                    val idIdx = it.getColumnIndex(CalendarContract.Calendars._ID)
                    calendarId = it.getLong(idIdx)
                }
            }
        }
        
        if (calendarId == -1L) return false

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startTime)
            put(CalendarContract.Events.DTEND, endTime)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        val uri: Uri? = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return uri != null
    }

    override fun queryEvents(keyword: String): List<CalendarEvent> {
        val resolver: ContentResolver = context.contentResolver
        val events = mutableListOf<CalendarEvent>()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION
        )

        val selection = "${CalendarContract.Events.TITLE} LIKE ? OR ${CalendarContract.Events.DESCRIPTION} LIKE ?"
        val selectionArgs = arrayOf("%$keyword%", "%$keyword%")

        val cursor = resolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} DESC"
        )

        cursor?.use {
            val idIdx = it.getColumnIndex(CalendarContract.Events._ID)
            val titleIdx = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startIdx = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIdx = it.getColumnIndex(CalendarContract.Events.DTEND)
            val descIdx = it.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val locIdx = it.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)

            while (it.moveToNext()) {
                events.add(
                    CalendarEvent(
                        id = it.getString(idIdx),
                        title = it.getString(titleIdx) ?: "",
                        startTime = it.getLong(startIdx),
                        endTime = it.getLong(endIdx),
                        description = it.getString(descIdx),
                        location = it.getString(locIdx)
                    )
                )
            }
        }
        return events
    }
}
