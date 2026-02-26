package com.example.calendarcomplication.complication

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MainComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val TAG = "MainComplicationService"
        private const val IMAGE_SIZE = 450
        private const val TICK_MS = 60_000L
        private const val CALENDAR_CHANGE_DEBOUNCE_MS = 350L

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
    private var calendarObserverRegistered = false

    private val calendarChangeRefreshRunnable = Runnable {
        if (activePhotoComplicationIds.isNotEmpty()) {
            forceUpdateNow(this@MainComplicationService)
        }
    }

    private val calendarObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            onCalendarDataChanged()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onCalendarDataChanged()
        }
    }

    private val minuteTicker = object : Runnable {
        override fun run() {
            if (!tickerRunning) {
                return
            }

            forceUpdateNow(this@MainComplicationService)

            val now = System.currentTimeMillis()
            val delay = TICK_MS - (now % TICK_MS)
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
        registerCalendarObserverIfNeeded()
        mainHandler.removeCallbacks(minuteTicker)

        val now = System.currentTimeMillis()
        val firstDelay = TICK_MS - (now % TICK_MS)
        forceUpdateNow(this)
        mainHandler.postDelayed(minuteTicker, firstDelay)
    }

    private fun stopTicker() {
        tickerRunning = false
        mainHandler.removeCallbacks(minuteTicker)
        mainHandler.removeCallbacks(calendarChangeRefreshRunnable)
        unregisterCalendarObserverIfNeeded()
    }

    private fun onCalendarDataChanged() {
        if (activePhotoComplicationIds.isEmpty()) {
            return
        }
        mainHandler.removeCallbacks(calendarChangeRefreshRunnable)
        mainHandler.postDelayed(calendarChangeRefreshRunnable, CALENDAR_CHANGE_DEBOUNCE_MS)
    }

    private fun registerCalendarObserverIfNeeded() {
        if (calendarObserverRegistered) {
            return
        }

        runCatching {
            contentResolver.registerContentObserver(WEARABLE_PROVIDER_BASE_URI, true, calendarObserver)
        }.onSuccess {
            calendarObserverRegistered = true
        }.onFailure {
            Log.w(TAG, "Failed to register calendar observer", it)
        }
    }

    private fun unregisterCalendarObserverIfNeeded() {
        if (!calendarObserverRegistered) {
            return
        }

        runCatching {
            contentResolver.unregisterContentObserver(calendarObserver)
        }.onFailure {
            Log.w(TAG, "Failed to unregister calendar observer", it)
        }
        calendarObserverRegistered = false
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
            val titleIndex = cursor.getColumnIndex("title")

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
                        title = if (titleIndex >= 0) cursor.getString(titleIndex) else null,
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
            val labelPaint = Paint().apply {
                color = Color.rgb(230, 236, 246)
                textSize = 20f
                textAlign = Paint.Align.LEFT
                isAntiAlias = true
            }
            val labelShadowPaint = Paint().apply {
                color = Color.argb(150, 0, 0, 0)
                textSize = 20f
                textAlign = Paint.Align.LEFT
                isAntiAlias = true
            }
            val dayDuration = probe.dayEndMillis - probe.dayStartMillis
            val labelRingPadding = 12f
            val centerKeepoutRadius = 75f
            val maxLabelWidth = (ringRadius - labelRingPadding - centerKeepoutRadius).coerceAtLeast(18f)
            val anchorRadius = ringRadius

            data class LabelCandidate(
                val label: String,
                val anchorX: Float,
                val anchorY: Float,
                val rotation: Float,
                val textStartX: Float,
                val textBaselineY: Float,
                val centerX: Float,
                val centerY: Float,
                val priority: Float
            )

            data class RenderedSegment(
                val startMillis: Long,
                val endMillis: Long,
                val startDegrees: Float,
                val color: Int
            )

            val labelCandidates = mutableListOf<LabelCandidate>()
            val renderedSegments = mutableListOf<RenderedSegment>()

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
                renderedSegments.add(
                    RenderedSegment(
                        startMillis = clippedStart,
                        endMillis = clippedEnd,
                        startDegrees = startDegrees,
                        color = event.color
                    )
                )

                val title = event.title?.trim().orEmpty()
                if (title.isBlank()) {
                    continue
                }

                val label = fitLabelToWidth(title, labelPaint, maxLabelWidth)
                if (label.isBlank()) {
                    continue
                }
                val textWidth = labelPaint.measureText(label)

                val midDegrees = startDegrees + (sweepDegrees / 2f)
                val midRadians = Math.toRadians(midDegrees.toDouble())
                val anchorX = center + (cos(midRadians) * anchorRadius).toFloat()
                val anchorY = center + (sin(midRadians) * anchorRadius).toFloat()

                val normalizedAngle = ((midDegrees % 360f) + 360f) % 360f
                val isFlippedForReadability = normalizedAngle > 90f && normalizedAngle < 270f
                val labelRotation = if (isFlippedForReadability) {
                    normalizedAngle + 180f
                } else {
                    normalizedAngle
                }

                val textStartX = if (isFlippedForReadability) {
                    labelRingPadding
                } else {
                    -(textWidth + labelRingPadding)
                }
                val textCenterOffset = textStartX + (textWidth / 2f)
                val rotationRadians = Math.toRadians(labelRotation.toDouble())
                val textCenterX = anchorX + (cos(rotationRadians) * textCenterOffset).toFloat()
                val textCenterY = anchorY + (sin(rotationRadians) * textCenterOffset).toFloat()
                val textBaselineY = -((labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f)

                labelCandidates.add(
                    LabelCandidate(
                        label = label,
                        anchorX = anchorX,
                        anchorY = anchorY,
                        rotation = labelRotation,
                        textStartX = textStartX,
                        textBaselineY = textBaselineY,
                        centerX = textCenterX,
                        centerY = textCenterY,
                        priority = sweepDegrees
                    )
                )
            }

            val separatorPaint = Paint().apply {
                color = Color.argb(220, 18, 18, 18)
                style = Paint.Style.STROKE
                strokeWidth = 2f
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            }
            val separatorInner = ringRadius - 7f
            val separatorOuter = ringRadius + 7f
            for (i in 1 until renderedSegments.size) {
                val previous = renderedSegments[i - 1]
                val current = renderedSegments[i]
                val isSameColor = previous.color == current.color
                val isAdjacentInTime = abs(current.startMillis - previous.endMillis) <= 60_000L
                if (!isSameColor || !isAdjacentInTime) {
                    continue
                }

                val radians = Math.toRadians(current.startDegrees.toDouble())
                val x1 = center + (cos(radians) * separatorInner).toFloat()
                val y1 = center + (sin(radians) * separatorInner).toFloat()
                val x2 = center + (cos(radians) * separatorOuter).toFloat()
                val y2 = center + (sin(radians) * separatorOuter).toFloat()
                canvas.drawLine(x1, y1, x2, y2, separatorPaint)
            }

            val placedLabelCenters = mutableListOf<Pair<Float, Float>>()
            for (candidate in labelCandidates.sortedByDescending { it.priority }) {
                val minDistance = 28f
                val minDistanceSq = minDistance * minDistance
                val overlapsExisting = placedLabelCenters.any { (x, y) ->
                    val dx = candidate.centerX - x
                    val dy = candidate.centerY - y
                    (dx * dx) + (dy * dy) < minDistanceSq
                }
                if (overlapsExisting) {
                    continue
                }

                placedLabelCenters.add(candidate.centerX to candidate.centerY)
                canvas.save()
                canvas.translate(candidate.anchorX, candidate.anchorY)
                canvas.rotate(candidate.rotation)
                canvas.drawText(
                    candidate.label,
                    candidate.textStartX + 1f,
                    candidate.textBaselineY + 1f,
                    labelShadowPaint
                )
                canvas.drawText(candidate.label, candidate.textStartX, candidate.textBaselineY, labelPaint)
                canvas.restore()
            }

            val now = System.currentTimeMillis()
            if (dayDuration > 0L) {
                val nowFraction = ((now - probe.dayStartMillis).toDouble() / dayDuration.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
                val passedSweepDegrees = nowFraction * 360f
                if (passedSweepDegrees > 0f) {
                    val dimPaint = Paint().apply {
                        color = Color.argb(140, 0, 0, 0)
                        style = Paint.Style.STROKE
                        strokeWidth = 14f
                        strokeCap = Paint.Cap.BUTT
                        isAntiAlias = true
                    }
                    canvas.drawArc(ringRect, -90f, passedSweepDegrees, false, dimPaint)
                }

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

        val nowCal = java.util.Calendar.getInstance()
        val hour12Raw = nowCal.get(java.util.Calendar.HOUR)
        val hour12 = if (hour12Raw == 0) 12 else hour12Raw
        val minute = nowCal.get(java.util.Calendar.MINUTE)
        val amPm = if (nowCal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        val timeText = String.format(Locale.getDefault(), "%d:%02d", hour12, minute)

        val timeShadowPaint = Paint().apply {
            color = Color.argb(170, 0, 0, 0)
            textSize = 52f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val timePaint = Paint().apply {
            color = Color.rgb(236, 242, 255)
            textSize = 52f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val amPmPaint = Paint().apply {
            color = Color.rgb(172, 196, 255)
            textSize = 20f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        canvas.drawText(timeText, center + 1f, center + 16f, timeShadowPaint)
        canvas.drawText(timeText, center, center + 15f, timePaint)
        canvas.drawText(amPm, center, center + 35f, amPmPaint)

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
    val title: String?,
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

private fun fitLabelToWidth(title: String, paint: Paint, maxWidthPx: Float): String {
    val trimmed = title.trim()
    if (trimmed.isBlank()) {
        return ""
    }
    if (paint.measureText(trimmed) <= maxWidthPx) {
        return trimmed
    }

    val ellipsis = "..."
    var end = trimmed.length
    while (end > 1) {
        val candidate = trimmed.substring(0, end).trimEnd() + ellipsis
        if (paint.measureText(candidate) <= maxWidthPx) {
            return candidate
        }
        end -= 1
    }
    return ""
}
