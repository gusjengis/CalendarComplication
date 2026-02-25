package com.example.calendarcomplication.complication

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import java.util.Collections
import kotlin.math.cos
import kotlin.math.sin

class MainComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val TAG = "MainComplicationService"
        private const val IMAGE_SIZE = 450
        private const val MINUTE_MS = 60_000L

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

    private val activePhotoComplicationIds = Collections.synchronizedSet(mutableSetOf<Int>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunning = false

    private val minuteTicker = object : Runnable {
        override fun run() {
            if (!tickerRunning) {
                return
            }

            forceUpdateNow(this@MainComplicationService)

            val now = System.currentTimeMillis()
            val delay = MINUTE_MS - (now % MINUTE_MS)
            mainHandler.postDelayed(this, delay)
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.PHOTO_IMAGE) {
            return null
        }

        val preview = CalendarProbeResult(
            status = "PREVIEW MODE",
            detail = "Calendar probe runs in live request",
            account = "Account: preview",
            sync = "sync: wearable managed"
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

        activePhotoComplicationIds.add(request.complicationInstanceId)
        startTickerIfNeeded()

        val probe = probeCalendarDataAccess()
        Log.d(TAG, "Calendar probe: ${probe.status} (${probe.detail})")

        return PhotoImageComplicationData.Builder(
            photoImage = Icon.createWithBitmap(generateStatusBitmap(probe)),
            contentDescription = PlainComplicationText.Builder("Calendar ring status").build()
        ).build()
    }

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        if (type == ComplicationType.PHOTO_IMAGE) {
            activePhotoComplicationIds.add(complicationInstanceId)
            startTickerIfNeeded()
        }
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        super.onComplicationDeactivated(complicationInstanceId)
        activePhotoComplicationIds.remove(complicationInstanceId)
        if (activePhotoComplicationIds.isEmpty()) {
            stopTicker()
        }
    }

    override fun onDestroy() {
        stopTicker()
        super.onDestroy()
    }

    private fun startTickerIfNeeded() {
        if (tickerRunning || activePhotoComplicationIds.isEmpty()) {
            return
        }

        tickerRunning = true
        mainHandler.removeCallbacks(minuteTicker)

        val now = System.currentTimeMillis()
        val firstDelay = MINUTE_MS - (now % MINUTE_MS)
        mainHandler.postDelayed(minuteTicker, firstDelay)
    }

    private fun stopTicker() {
        tickerRunning = false
        mainHandler.removeCallbacks(minuteTicker)
    }

    private fun probeCalendarDataAccess(): CalendarProbeResult {
        if (!hasCalendarPermission()) {
            return CalendarProbeResult(
                status = "NO CALENDAR PERMISSION",
                detail = "Grant READ_CALENDAR to provider app",
                account = "Account: unavailable",
                sync = "sync: wearable managed"
            )
        }

        return try {
            val now = System.currentTimeMillis()
            val dayBounds = localDayBounds(now)
            val dayEnd = now + 24L * 60L * 60L * 1000L
            val weekEnd = now + 7L * 24L * 60L * 60L * 1000L

            val raw = loadWearableEventsRaw(now, weekEnd)
            val todayRaw = loadWearableEventsRaw(dayBounds.startMillis, dayBounds.endMillis)
            val events24h = raw.events.filter { overlapsRange(it, now, dayEnd) }
            val accountSummary = raw.accountHint?.takeIf { it.isNotBlank() } ?: "unavailable"

            val detail =
                "src:wearable 24h:${events24h.size}  7d:${raw.events.size}  raw:${raw.rawCount}"

            CalendarProbeResult(
                status = "CALENDAR ACCESS OK",
                detail = detail,
                account = "Account: $accountSummary",
                sync = "sync: wearable managed",
                dayEvents = todayRaw.events,
                dayStartMillis = dayBounds.startMillis,
                dayEndMillis = dayBounds.endMillis
            )
        } catch (t: Throwable) {
            CalendarProbeResult(
                status = "CALENDAR READ ERROR",
                detail = t.javaClass.simpleName,
                account = "Account: unavailable",
                sync = "sync: wearable managed",
                dayEvents = emptyList(),
                dayStartMillis = 0L,
                dayEndMillis = 0L
            )
        }
    }

    private fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadWearableEventsRaw(startMillis: Long, endMillis: Long): WearableEventsRaw {
        val events = mutableListOf<CalendarEventStub>()
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

            firstExistingColumnIndex(cursor, listOf("_id", "event_id"))
            val startIndex = firstExistingColumnIndex(cursor, listOf("begin", "dtstart", "start", "startTime"))
            val endIndex = firstExistingColumnIndex(cursor, listOf("end", "dtend", "endTime"))
            val accountIndex = firstExistingColumnIndex(cursor, listOf("ownerAccount", "account_name", "organizer"))
            val eventColorIndex = cursor.getColumnIndex("eventColor")
            val calendarColorIndex = cursor.getColumnIndex("calendar_color")
            val displayColorIndex = cursor.getColumnIndex("displayColor")

            while (cursor.moveToNext()) {
                val start = normalizeEpochMillis(getLongCompat(cursor, startIndex))
                val end = normalizeEpochMillis(getLongCompat(cursor, endIndex))
                if (start <= 0L || end <= 0L || end < start) {
                    continue
                }

                val owner = cursor.getString(accountIndex)
                if (accountHint.isNullOrBlank() && !owner.isNullOrBlank()) {
                    accountHint = owner
                }

                events.add(
                    CalendarEventStub(
                        startMillis = start,
                        endMillis = end,
                        color = resolveEventColor(
                            displayColor = getNullableColorCompat(cursor, displayColorIndex),
                            eventColor = getNullableColorCompat(cursor, eventColorIndex),
                            calendarColor = getNullableColorCompat(cursor, calendarColorIndex)
                        )
                    )
                )
            }
        }

        events.sortBy { it.startMillis }

        return WearableEventsRaw(
            events = events,
            rawCount = rawCount,
            accountHint = accountHint
        )
    }

    private fun wearableInstancesUri(startMillis: Long, endMillis: Long): Uri {
        return WEARABLE_PROVIDER_BASE_URI.buildUpon()
            .appendPath("instances")
            .appendPath("when")
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()
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
        val color = when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getInt(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toInt()
            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)?.toIntOrNull()
            else -> null
        }
        if (color == null || color == 0) {
            return null
        }
        return color
    }

    private fun resolveEventColor(displayColor: Int?, eventColor: Int?, calendarColor: Int?): Int {
        return displayColor ?: eventColor ?: calendarColor ?: Color.rgb(120, 120, 120)
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

    private fun localDayBounds(nowMillis: Long): DayBounds {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis
        calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val end = calendar.timeInMillis
        return DayBounds(startMillis = start, endMillis = end)
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
        val ringRadius = center - 18f
        canvas.drawCircle(center, center, ringRadius, ringPaint)

        if (probe.dayStartMillis > 0L && probe.dayEndMillis > probe.dayStartMillis) {
            val ringRect = RectF(
                center - ringRadius,
                center - ringRadius,
                center + ringRadius,
                center + ringRadius
            )
            val arcPaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 14f
                strokeCap = Paint.Cap.BUTT
                isAntiAlias = true
            }
            val dayDuration = probe.dayEndMillis - probe.dayStartMillis

            for (event in probe.dayEvents) {
                val clippedStart = maxOf(event.startMillis, probe.dayStartMillis)
                val clippedEnd = minOf(event.endMillis, probe.dayEndMillis)
                if (clippedEnd <= clippedStart) {
                    continue
                }

                val startFraction = (clippedStart - probe.dayStartMillis).toFloat() / dayDuration.toFloat()
                val endFraction = (clippedEnd - probe.dayStartMillis).toFloat() / dayDuration.toFloat()
                val startDegrees = -90f + (startFraction * 360f)
                val sweepDegrees = (endFraction - startFraction) * 360f
                if (sweepDegrees <= 0f) {
                    continue
                }

                arcPaint.color = event.color
                canvas.drawArc(ringRect, startDegrees, sweepDegrees, false, arcPaint)
            }

            val now = System.currentTimeMillis()
            if (dayDuration > 0L) {
                val nowFraction = ((now - probe.dayStartMillis).toDouble() / dayDuration.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
                val nowDegrees = -90f + (nowFraction * 360f)
                val nowRadians = Math.toRadians(nowDegrees.toDouble())

                val markerPaint = Paint().apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                    strokeCap = Paint.Cap.ROUND
                    isAntiAlias = true
                }

                val inner = ringRadius - 12f
                val outer = ringRadius + 12f
                val x1 = center + (cos(nowRadians) * inner).toFloat()
                val y1 = center + (sin(nowRadians) * inner).toFloat()
                val x2 = center + (cos(nowRadians) * outer).toFloat()
                val y2 = center + (sin(nowRadians) * outer).toFloat()

                canvas.drawLine(x1, y1, x2, y2, markerPaint)
            }
        }

        return bitmap
    }
}

private data class CalendarProbeResult(
    val status: String,
    val detail: String,
    val account: String,
    val sync: String,
    val dayEvents: List<CalendarEventStub> = emptyList(),
    val dayStartMillis: Long = 0L,
    val dayEndMillis: Long = 0L
)

private data class CalendarEventStub(
    val startMillis: Long,
    val endMillis: Long,
    val color: Int
)

private data class WearableEventsRaw(
    val events: List<CalendarEventStub>,
    val rawCount: Int,
    val accountHint: String?
)

private data class DayBounds(
    val startMillis: Long,
    val endMillis: Long
)
