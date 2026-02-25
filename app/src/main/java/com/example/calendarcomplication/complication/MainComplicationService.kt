package com.example.calendarcomplication.complication

import android.Manifest
import android.accounts.Account
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.EmptyComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class MainComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val TAG = "MainComplicationService"
        private const val IMAGE_SIZE = 450
        private const val CALENDAR_AUTHORITY = "com.google.android.calendar"
        private const val MIN_SYNC_REQUEST_INTERVAL_MS = 5L * 60L * 1000L
        private const val SAMSUNG_CALENDAR_READ_PERMISSION =
            "com.samsung.android.calendar.permission.READ"

        private val SAMSUNG_CALENDARS_URI: Uri =
            Uri.parse("content://com.samsung.android.calendar.watch/calendars")
        private val SAMSUNG_EVENTS_URI: Uri =
            Uri.parse("content://com.samsung.android.calendar.watch/events")
        private val WEARABLE_PROVIDER_BASE_URI: Uri =
            Uri.parse("content://com.google.android.wearable.provider.calendar")

        fun forceUpdateNow(context: Context) {
            val requester = ComplicationDataSourceUpdateRequester.create(
                context,
                ComponentName(context, MainComplicationService::class.java)
            )
            requester.requestUpdateAll()
        }
    }

    private var lastSyncRequestAtMs: Long = 0L
    private var syncStatusLine: String = "sync: idle"

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.PHOTO_IMAGE) {
            return null
        }

        val preview = CalendarProbeResult(
            status = "PREVIEW MODE",
            detail = "Calendar probe runs in live request",
            account = "Account: preview",
            sync = "sync: preview"
        )

        return PhotoImageComplicationData.Builder(
            photoImage = Icon.createWithBitmap(generateStatusBitmap(preview)),
            contentDescription = PlainComplicationText.Builder("Calendar ring preview").build()
        ).build()
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        if (request.complicationType != ComplicationType.PHOTO_IMAGE) {
            return EmptyComplicationData()
        }

        maybeRequestCalendarSync()
        val probe = probeCalendarDataAccess()
        Log.d(TAG, "Calendar probe: ${probe.status} (${probe.detail}) ${probe.sync}")

        return PhotoImageComplicationData.Builder(
            photoImage = Icon.createWithBitmap(generateStatusBitmap(probe)),
            contentDescription = PlainComplicationText.Builder("Calendar ring status").build()
        ).build()
    }

    private fun probeCalendarDataAccess(): CalendarProbeResult {
        if (!hasCalendarPermission()) {
            return CalendarProbeResult(
                status = "NO CALENDAR PERMISSION",
                detail = "Grant READ_CALENDAR to provider app",
                account = "Account: unavailable",
                sync = syncStatusLine
            )
        }

        return try {
            val backend = resolveCalendarBackend()
            val allCalendars = loadAllCalendars(backend)
            val visibleCalendars = allCalendars.filter { it.visible != 0 }
            val now = System.currentTimeMillis()
            val dayEnd = now + 24L * 60L * 60L * 1000L
            val weekEnd = now + 7L * 24L * 60L * 60L * 1000L

            val events24h: List<CalendarEventStub>
            val events7d: List<CalendarEventStub>
            var rawEventCount = -1
            var schemaInfo = ""
            var fallbackAccountFromEvents: String? = null

            if (backend == CalendarBackend.SAMSUNG_WATCH) {
                val raw = loadSamsungEventsRaw()
                rawEventCount = raw.rawCount
                schemaInfo = " cols:${raw.startColumn}/${raw.endColumn}"
                fallbackAccountFromEvents = raw.accountHint
                events24h = raw.events.filter { overlapsRange(it, now, dayEnd) }
                events7d = raw.events.filter { overlapsRange(it, now, weekEnd) }
            } else if (backend == CalendarBackend.WEARABLE_PROVIDER) {
                val raw = loadWearableEventsRaw(now, weekEnd)
                rawEventCount = raw.rawCount
                schemaInfo = " cols:${raw.startColumn}/${raw.endColumn}"
                fallbackAccountFromEvents = raw.accountHint
                events24h = raw.events.filter { overlapsRange(it, now, dayEnd) }
                events7d = raw.events.filter { overlapsRange(it, now, weekEnd) }
            } else {
                events24h = loadEventsForRange(
                    backend = backend,
                    startMillis = now,
                    endMillis = dayEnd
                )
                events7d = loadEventsForRange(
                    backend = backend,
                    startMillis = now,
                    endMillis = weekEnd
                )
            }

            val accountSummary = if (allCalendars.isEmpty()) {
                if (!fallbackAccountFromEvents.isNullOrBlank()) {
                    "Account: $fallbackAccountFromEvents"
                } else {
                    "Account: no calendars"
                }
            } else if (backend == CalendarBackend.WEARABLE_PROVIDER && !fallbackAccountFromEvents.isNullOrBlank()) {
                "Account: $fallbackAccountFromEvents"
            } else {
                val first = allCalendars.first()
                val extra = allCalendars.size - 1
                if (extra > 0) {
                    "Account: ${first.accountName} (+$extra)"
                } else {
                    "Account: ${first.accountName}"
                }
            }

            val detail = buildString {
                append("src:${backend.label} 24h:${events24h.size}  7d:${events7d.size}  cal:${allCalendars.size}/${visibleCalendars.size}")
                if (rawEventCount >= 0) {
                    append(" raw:")
                    append(rawEventCount)
                    append(schemaInfo)
                }
            }

            CalendarProbeResult(
                status = "CALENDAR ACCESS OK",
                detail = detail,
                account = accountSummary,
                sync = syncStatusLine
            )
        } catch (t: Throwable) {
            CalendarProbeResult(
                status = "CALENDAR READ ERROR",
                detail = t.javaClass.simpleName,
                account = "Account: unavailable",
                sync = syncStatusLine
            )
        }
    }

    private fun maybeRequestCalendarSync() {
        if (!hasCalendarPermission()) {
            syncStatusLine = "sync: no permission"
            return
        }

        val backend = resolveCalendarBackend()
        if (backend == CalendarBackend.SAMSUNG_WATCH) {
            syncStatusLine = "sync: samsung managed"
            return
        }
        if (backend == CalendarBackend.WEARABLE_PROVIDER) {
            syncStatusLine = "sync: wearable managed"
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastSyncRequestAtMs < MIN_SYNC_REQUEST_INTERVAL_MS) {
            val ageSec = (now - lastSyncRequestAtMs) / 1000L
            syncStatusLine = "sync: throttled ${ageSec}s"
            return
        }

        val accounts = loadAllCalendars(backend)
            .map { it.accountName }
            .distinct()
            .filter { it.isNotBlank() }

        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }

        if (accounts.isEmpty()) {
            ContentResolver.requestSync(
                null,
                CALENDAR_AUTHORITY,
                extras
            )
            lastSyncRequestAtMs = now
            syncStatusLine = "sync: requested all accts"
            return
        }

        accounts.forEach { accountName ->
            ContentResolver.requestSync(Account(accountName, "com.google"), CALENDAR_AUTHORITY, extras)
        }

        lastSyncRequestAtMs = now
        syncStatusLine = "sync: requested ${accounts.size} acct"
    }

    private fun hasCalendarPermission(): Boolean {
        val hasAndroidRead = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        val hasSamsungRead = ContextCompat.checkSelfPermission(
            this,
            SAMSUNG_CALENDAR_READ_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED

        return hasAndroidRead || hasSamsungRead
    }

    private fun loadEventsForRange(
        backend: CalendarBackend,
        startMillis: Long,
        endMillis: Long
    ): List<CalendarEventStub> {
        return when (backend) {
            CalendarBackend.SAMSUNG_WATCH -> loadEventsForRangeSamsung(startMillis, endMillis)
            CalendarBackend.WEARABLE_PROVIDER -> loadEventsForRangeWearable(startMillis, endMillis)
            CalendarBackend.ANDROID_PROVIDER -> loadEventsForRangeAndroid(startMillis, endMillis)
            CalendarBackend.NONE -> emptyList()
        }
    }

    private fun loadEventsForRangeAndroid(
        startMillis: Long,
        endMillis: Long
    ): List<CalendarEventStub> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, startMillis)
            ContentUris.appendId(this, endMillis)
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE
        )

        val events = mutableListOf<CalendarEventStub>()
        contentResolver.query(
            uri,
            projection,
            CalendarContract.Instances.VISIBLE + "=1",
            null,
            CalendarContract.Instances.BEGIN + " ASC"
        )?.use { cursor ->
            val eventIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)

            while (cursor.moveToNext()) {
                events.add(
                    CalendarEventStub(
                        eventId = cursor.getLong(eventIdIndex),
                        startMillis = cursor.getLong(beginIndex),
                        endMillis = cursor.getLong(endIndex),
                        title = cursor.getString(titleIndex) ?: "(untitled)"
                    )
                )
            }
        }

        return events
    }

    private fun loadEventsForRangeSamsung(
        startMillis: Long,
        endMillis: Long
    ): List<CalendarEventStub> {
        return loadSamsungEventsRaw().events.filter { overlapsRange(it, startMillis, endMillis) }
    }

    private fun loadEventsForRangeWearable(
        startMillis: Long,
        endMillis: Long
    ): List<CalendarEventStub> {
        return loadWearableEventsRaw(startMillis, endMillis).events
    }

    private fun loadSamsungEventsRaw(): SamsungEventsRaw {
        val events = mutableListOf<CalendarEventStub>()
        var startColumn = "?"
        var endColumn = "?"
        var accountHint: String? = null
        var rawCount = 0

        contentResolver.query(
            SAMSUNG_EVENTS_URI,
            null,
            null,
            null,
            null
        )?.use { cursor ->
            rawCount = cursor.count
            val idIndex = firstExistingColumnIndex(cursor, listOf("_id", "event_id"))
            val startPair = firstExistingColumn(cursor, listOf("dtstart", "begin", "start", "startTime", "start_time"))
            val endPair = firstExistingColumn(cursor, listOf("dtend", "end", "endTime", "end_time"))
            val titlePair = firstExistingColumn(cursor, listOf("title", "eventTitle", "subject"))
            val accountPair = firstExistingColumn(cursor, listOf("ownerAccount", "account_name", "organizer"))

            startColumn = startPair.first
            endColumn = endPair.first

            val startIndex = startPair.second
            val endIndex = endPair.second
            val titleIndex = titlePair.second
            val accountIndex = accountPair?.second

            while (cursor.moveToNext()) {
                val rawStart = getLongCompat(cursor, startIndex)
                val rawEnd = getLongCompat(cursor, endIndex)
                val normalizedStart = normalizeEpochMillis(rawStart)
                val normalizedEnd = normalizeEpochMillis(rawEnd)
                if (normalizedStart <= 0L || normalizedEnd <= 0L) {
                    continue
                }
                if (normalizedEnd < normalizedStart) {
                    continue
                }

                if (accountHint.isNullOrBlank() && accountIndex != null) {
                    accountHint = cursor.getString(accountIndex)
                }

                events.add(
                    CalendarEventStub(
                        eventId = cursor.getLong(idIndex),
                        startMillis = normalizedStart,
                        endMillis = normalizedEnd,
                        title = if (titleIndex >= 0) cursor.getString(titleIndex) ?: "(untitled)" else "(untitled)"
                    )
                )
            }
        }

        return SamsungEventsRaw(
            events = events,
            rawCount = rawCount,
            startColumn = startColumn,
            endColumn = endColumn,
            accountHint = accountHint
        )
    }

    private fun loadWearableEventsRaw(startMillis: Long, endMillis: Long): SamsungEventsRaw {
        val events = mutableListOf<CalendarEventStub>()
        var startColumn = "?"
        var endColumn = "?"
        var accountHint: String? = null
        var rawCount = 0

        contentResolver.query(
            wearableInstancesUri(startMillis, endMillis),
            null,
            null,
            null,
            null
        )?.use { cursor ->
            rawCount = cursor.count
            val idIndex = firstExistingColumnIndex(cursor, listOf("_id", "event_id"))
            val startPair = firstExistingColumn(cursor, listOf("begin", "dtstart", "start", "startTime"))
            val endPair = firstExistingColumn(cursor, listOf("end", "dtend", "endTime"))
            val titlePair = firstExistingColumn(cursor, listOf("title", "eventTitle", "subject"))
            val accountPair = firstExistingColumn(cursor, listOf("ownerAccount", "account_name", "organizer"))

            startColumn = startPair.first
            endColumn = endPair.first

            val startIndex = startPair.second
            val endIndex = endPair.second
            val titleIndex = titlePair.second
            val accountIndex = accountPair.second

            while (cursor.moveToNext()) {
                val normalizedStart = normalizeEpochMillis(getLongCompat(cursor, startIndex))
                val normalizedEnd = normalizeEpochMillis(getLongCompat(cursor, endIndex))
                if (normalizedStart <= 0L || normalizedEnd <= 0L) {
                    continue
                }
                if (normalizedEnd < normalizedStart) {
                    continue
                }

                if (accountHint.isNullOrBlank()) {
                    accountHint = cursor.getString(accountIndex)
                }

                events.add(
                    CalendarEventStub(
                        eventId = cursor.getLong(idIndex),
                        startMillis = normalizedStart,
                        endMillis = normalizedEnd,
                        title = if (titleIndex >= 0) cursor.getString(titleIndex) ?: "(untitled)" else "(untitled)"
                    )
                )
            }
        }

        return SamsungEventsRaw(
            events = events,
            rawCount = rawCount,
            startColumn = startColumn,
            endColumn = endColumn,
            accountHint = accountHint
        )
    }

    private fun loadVisibleCalendars(backend: CalendarBackend): List<CalendarSourceStub> {
        return loadCalendars(backend = backend, visibleOnly = true)
    }

    private fun loadAllCalendars(backend: CalendarBackend): List<CalendarSourceStub> {
        return loadCalendars(backend = backend, visibleOnly = false)
    }

    private fun loadCalendars(
        backend: CalendarBackend,
        visibleOnly: Boolean
    ): List<CalendarSourceStub> {
        if (backend == CalendarBackend.NONE) {
            return emptyList()
        }

        if (backend == CalendarBackend.WEARABLE_PROVIDER) {
            val now = System.currentTimeMillis()
            val horizon = now + 30L * 24L * 60L * 60L * 1000L
            val rows = loadWearableEventsRaw(now, horizon).events
            if (rows.isEmpty()) {
                return emptyList()
            }
            return listOf(
                CalendarSourceStub(
                    calendarId = 1L,
                    accountName = "wearable-provider",
                    calendarDisplayName = "Events",
                    visible = 1
                )
            )
        }

        val projection = arrayOf("_id", "account_name", "calendar_displayName", "visible")
        val uri = if (backend == CalendarBackend.SAMSUNG_WATCH) {
            SAMSUNG_CALENDARS_URI
        } else {
            CalendarContract.Calendars.CONTENT_URI
        }

        val calendars = mutableListOf<CalendarSourceStub>()
        contentResolver.query(
            uri,
            projection,
            if (visibleOnly && backend == CalendarBackend.ANDROID_PROVIDER) "visible=1" else null,
            null,
            "account_name ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("_id")
            val accountIndex = cursor.getColumnIndexOrThrow("account_name")
            val displayNameIndex = cursor.getColumnIndexOrThrow("calendar_displayName")
            val visibleIndex = cursor.getColumnIndex("visible")

            while (cursor.moveToNext()) {
                val visible = if (visibleIndex >= 0) cursor.getInt(visibleIndex) else 1
                if (visibleOnly && visible == 0) {
                    continue
                }
                calendars.add(
                    CalendarSourceStub(
                        calendarId = cursor.getLong(idIndex),
                        accountName = cursor.getString(accountIndex) ?: "(no account)",
                        calendarDisplayName = cursor.getString(displayNameIndex) ?: "(no name)",
                        visible = visible
                    )
                )
            }
        }

        return calendars
    }

    private fun resolveCalendarBackend(): CalendarBackend {
        val wearableAvailable = try {
            val now = System.currentTimeMillis()
            val soon = now + 2L * 24L * 60L * 60L * 1000L
            contentResolver.query(
                wearableInstancesUri(now, soon),
                arrayOf("_id"),
                null,
                null,
                null
            )?.use { _ -> }
            true
        } catch (_: Throwable) {
            false
        }
        if (wearableAvailable) {
            return CalendarBackend.WEARABLE_PROVIDER
        }

        val samsungAvailable = try {
            contentResolver.query(
                SAMSUNG_CALENDARS_URI,
                arrayOf("_id"),
                null,
                null,
                null
            )?.use { _ -> }
            true
        } catch (_: Throwable) {
            false
        }
        if (samsungAvailable) {
            return CalendarBackend.SAMSUNG_WATCH
        }

        val androidAvailable = try {
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                null,
                null,
                null
            )?.use { _ -> }
            true
        } catch (_: Throwable) {
            false
        }

        return if (androidAvailable) CalendarBackend.ANDROID_PROVIDER else CalendarBackend.NONE
    }

    private fun wearableInstancesUri(startMillis: Long, endMillis: Long): Uri {
        return WEARABLE_PROVIDER_BASE_URI.buildUpon()
            .appendPath("instances")
            .appendPath("when")
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()
    }

    private fun firstExistingColumnIndex(
        cursor: android.database.Cursor,
        names: List<String>
    ): Int {
        for (name in names) {
            val idx = cursor.getColumnIndex(name)
            if (idx >= 0) {
                return idx
            }
        }
        throw IllegalStateException("None of columns exist: $names")
    }

    private fun firstExistingColumn(
        cursor: android.database.Cursor,
        names: List<String>
    ): Pair<String, Int> {
        for (name in names) {
            val idx = cursor.getColumnIndex(name)
            if (idx >= 0) {
                return name to idx
            }
        }
        throw IllegalStateException("None of columns exist: $names")
    }

    private fun getLongCompat(cursor: android.database.Cursor, index: Int): Long {
        return when (cursor.getType(index)) {
            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toLong()
            android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)?.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun normalizeEpochMillis(value: Long): Long {
        if (value <= 0L) {
            return value
        }
        return when {
            value < 1_000_000_000_000L -> value * 1000L
            value > 1_000_000_000_000_000L -> value / 1_000_000L
            value > 10_000_000_000_000L -> value / 1000L
            else -> value
        }
    }

    private fun overlapsRange(event: CalendarEventStub, startMillis: Long, endMillis: Long): Boolean {
        return event.endMillis >= startMillis && event.startMillis <= endMillis
    }

    private fun generateStatusBitmap(probe: CalendarProbeResult): Bitmap {
        val bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val sizeF = IMAGE_SIZE.toFloat()
        val center = sizeF / 2f

        val bgPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, sizeF, sizeF, bgPaint)

        val ringPaint = Paint().apply {
            color = Color.rgb(72, 72, 72)
            style = Paint.Style.STROKE
            strokeWidth = 14f
            isAntiAlias = true
        }
        canvas.drawCircle(center, center, center - 18f, ringPaint)

        val statusPaint = Paint().apply {
            color = Color.WHITE
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val detailPaint = Paint().apply {
            color = Color.rgb(170, 170, 170)
            textSize = 22f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        drawCenteredTextBlock(
            canvas = canvas,
            centerX = center,
            centerY = center,
            lines = listOf("CALENDAR RING", probe.status, probe.detail, probe.account, probe.sync),
            paints = listOf(statusPaint, statusPaint, detailPaint, detailPaint, detailPaint),
            lineSpacing = 34f
        )

        return bitmap
    }

    private fun drawCenteredTextBlock(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        lines: List<String>,
        paints: List<Paint>,
        lineSpacing: Float
    ) {
        val totalHeight = lineSpacing * (lines.size - 1)
        var y = centerY - totalHeight / 2f

        for (i in lines.indices) {
            val paint = paints[i]
            val bounds = Rect()
            paint.getTextBounds(lines[i], 0, lines[i].length, bounds)
            canvas.drawText(lines[i], centerX, y + bounds.height() / 2f, paint)
            y += lineSpacing
        }
    }
}

private data class CalendarProbeResult(
    val status: String,
    val detail: String,
    val account: String,
    val sync: String
)

private data class CalendarEventStub(
    val eventId: Long,
    val startMillis: Long,
    val endMillis: Long,
    val title: String
)

private data class CalendarSourceStub(
    val calendarId: Long,
    val accountName: String,
    val calendarDisplayName: String,
    val visible: Int
)

private enum class CalendarBackend(val label: String) {
    WEARABLE_PROVIDER("wearable"),
    SAMSUNG_WATCH("samsung"),
    ANDROID_PROVIDER("android"),
    NONE("none")
}

private data class SamsungEventsRaw(
    val events: List<CalendarEventStub>,
    val rawCount: Int,
    val startColumn: String,
    val endColumn: String,
    val accountHint: String?
)
