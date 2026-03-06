package com.example.calendarcomplication.sync

import android.content.Context
import com.example.calendarcomplication.complication.CalendarEventStub
import org.json.JSONArray
import org.json.JSONObject

object PhoneCalendarSyncStore {
    private const val PREFS_NAME = "phone_calendar_sync"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_ACCOUNT_HINT = "account_hint"
    private const val KEY_EVENTS_JSON = "events_json"

    data class Snapshot(
        val updatedAtMillis: Long,
        val accountHint: String,
        val events: List<CalendarEventStub>
    )

    fun save(context: Context, updatedAtMillis: Long, accountHint: String, eventsJson: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_UPDATED_AT, updatedAtMillis)
            .putString(KEY_ACCOUNT_HINT, accountHint)
            .putString(KEY_EVENTS_JSON, eventsJson)
            .apply()
    }

    fun load(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updatedAtMillis = prefs.getLong(KEY_UPDATED_AT, 0L)
        val accountHint = prefs.getString(KEY_ACCOUNT_HINT, "phone") ?: "phone"
        val eventsJson = prefs.getString(KEY_EVENTS_JSON, null) ?: return null
        if (updatedAtMillis <= 0L) {
            return null
        }

        return Snapshot(
            updatedAtMillis = updatedAtMillis,
            accountHint = accountHint,
            events = parseEvents(eventsJson)
        )
    }

    private fun parseEvents(eventsJson: String): List<CalendarEventStub> {
        val events = mutableListOf<CalendarEventStub>()
        val array = runCatching { JSONArray(eventsJson) }.getOrElse { return emptyList() }
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val startMillis = obj.optLong("startMillis", 0L)
            val endMillis = obj.optLong("endMillis", 0L)
            if (startMillis <= 0L || endMillis <= startMillis) {
                continue
            }
            val title = if (obj.isNull("title")) null else obj.optString("title")
            events.add(
                CalendarEventStub(
                    eventId = obj.optLong("eventId", index.toLong()),
                    startMillis = startMillis,
                    endMillis = endMillis,
                    title = title,
                    isDailyRepeated = false,
                    color = obj.optInt("color", 0xFF787878.toInt())
                )
            )
        }
        return events.sortedBy { it.startMillis }
    }

    fun toJsonArray(events: List<CalendarEventStub>): String {
        val array = JSONArray()
        for (event in events) {
            val obj = JSONObject()
                .put("eventId", event.eventId)
                .put("startMillis", event.startMillis)
                .put("endMillis", event.endMillis)
                .put("title", event.title)
                .put("color", event.color)
            array.put(obj)
        }
        return array.toString()
    }
}
