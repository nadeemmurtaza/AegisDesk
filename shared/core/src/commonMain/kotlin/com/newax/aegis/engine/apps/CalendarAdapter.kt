package com.newax.aegis.engine.apps

data class CalendarEvent(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String?,
    val location: String?
)

interface CalendarAdapter {
    fun getUpcomingEvents(days: Int = 7): List<CalendarEvent>
    fun createEvent(title: String, startTime: Long, endTime: Long, description: String? = null, location: String? = null): Boolean
    fun queryEvents(keyword: String): List<CalendarEvent>
}
