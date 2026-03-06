package com.example.calendarcomplication.core.render

data class CalendarRenderEvent(
    val eventId: Long,
    val startMillis: Long,
    val endMillis: Long,
    val title: String?,
    val isDailyRepeated: Boolean,
    val color: Int
)

data class CalendarRenderProbe(
    val dayEvents: List<CalendarRenderEvent>,
    val dayStartMillis: Long,
    val dayEndMillis: Long,
    val nowMillis: Long = System.currentTimeMillis()
)
