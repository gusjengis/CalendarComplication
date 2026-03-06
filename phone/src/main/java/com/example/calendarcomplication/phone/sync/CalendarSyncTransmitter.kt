package com.example.calendarcomplication.phone.sync

import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.provider.CalendarContract
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object CalendarSyncTransmitter {
    private const val PATH_CALENDAR_SYNC = "/calendar_sync/events"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_ACCOUNT_HINT = "account_hint"
    private const val KEY_EVENTS_JSON = "events_json"

    private data class PhoneEvent(
        val eventId: Long,
        val startMillis: Long,
        val endMillis: Long,
        val title: String?,
        val color: Int,
        val accountHint: String?
    )

    fun sync(context: Context): Boolean {
        return runCatching {
            val now = System.currentTimeMillis()
            val bounds = threeDayBounds(now)
            val events = readPhoneCalendarEvents(context, bounds.first, bounds.second)
            val accountHint = events.firstNotNullOfOrNull { it.accountHint } ?: "phone"

            val dataRequest = PutDataMapRequest.create(PATH_CALENDAR_SYNC).apply {
                dataMap.putLong(KEY_UPDATED_AT, now)
                dataMap.putString(KEY_ACCOUNT_HINT, accountHint)
                dataMap.putString(KEY_EVENTS_JSON, toJson(events))
            }.asPutDataRequest().setUrgent()

            Tasks.await(Wearable.getDataClient(context).putDataItem(dataRequest))
            true
        }.getOrDefault(false)
    }

    private fun threeDayBounds(now: Long): Pair<Long, Long> {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis - 24L * 60L * 60L * 1000L
        val end = calendar.timeInMillis + (2L * 24L * 60L * 60L * 1000L)
        return start to end
    }

    private fun readPhoneCalendarEvents(context: Context, startMillis: Long, endMillis: Long): List<PhoneEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()

        val events = mutableListOf<PhoneEvent>()

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val eventIdIndex = firstExistingColumnIndex(cursor, listOf("event_id", CalendarContract.Instances.EVENT_ID, "_id"))
            val startIndex = firstExistingColumnIndex(cursor, listOf("begin", CalendarContract.Instances.BEGIN))
            val endIndex = firstExistingColumnIndex(cursor, listOf("end", CalendarContract.Instances.END))
            val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val displayColorIndex = cursor.getColumnIndex(CalendarContract.Instances.DISPLAY_COLOR)
            val eventColorIndex = cursor.getColumnIndex("eventColor")
            val calendarColorIndex = cursor.getColumnIndex("calendar_color")
            val accountIndex = firstExistingColumnIndex(
                cursor,
                listOf("ownerAccount", CalendarContract.Instances.OWNER_ACCOUNT, CalendarContract.Instances.ORGANIZER)
            )

            while (cursor.moveToNext()) {
                val start = getLongCompat(cursor, startIndex)
                val end = getLongCompat(cursor, endIndex)
                if (start <= 0L || end <= start) {
                    continue
                }

                events.add(
                    PhoneEvent(
                        eventId = getLongCompat(cursor, eventIdIndex),
                        startMillis = start,
                        endMillis = end,
                        title = if (titleIndex >= 0) cursor.getString(titleIndex) else null,
                        color = resolveEventColor(
                            getNullableColorCompat(cursor, displayColorIndex),
                            getNullableColorCompat(cursor, eventColorIndex),
                            getNullableColorCompat(cursor, calendarColorIndex)
                        ),
                        accountHint = if (accountIndex >= 0) cursor.getString(accountIndex) else null
                    )
                )
            }
        }

        return events.sortedBy { it.startMillis }
    }

    private fun firstExistingColumnIndex(cursor: Cursor, names: List<String>): Int {
        for (name in names) {
            val idx = cursor.getColumnIndex(name)
            if (idx >= 0) {
                return idx
            }
        }
        throw IllegalStateException("Missing expected columns: $names")
    }

    private fun getLongCompat(cursor: Cursor, index: Int): Long {
        return when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toLong()
            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)?.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun getNullableColorCompat(cursor: Cursor, index: Int): Int? {
        if (index < 0 || cursor.isNull(index)) {
            return null
        }
        return when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getInt(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toInt()
            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)?.toLongOrNull()?.toInt()
            else -> null
        }
    }

    private fun resolveEventColor(displayColor: Int?, eventColor: Int?, calendarColor: Int?): Int {
        return displayColor ?: eventColor ?: calendarColor ?: Color.rgb(120, 120, 120)
    }

    private fun toJson(events: List<PhoneEvent>): String {
        val array = JSONArray()
        for (event in events) {
            array.put(
                JSONObject()
                    .put("eventId", event.eventId)
                    .put("startMillis", event.startMillis)
                    .put("endMillis", event.endMillis)
                    .put("title", event.title)
                    .put("color", event.color)
            )
        }
        return array.toString()
    }
}
