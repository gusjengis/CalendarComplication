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
import com.example.calendarcomplication.settings.WatchSettingsStore
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
            if (!tickerRunning || activePhotoComplicationIds.isEmpty()) {
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
        startRefreshLoopIfNeeded()

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
            startRefreshLoopIfNeeded()
        }
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        super.onComplicationDeactivated(complicationInstanceId)
        activePhotoComplicationIds.remove(complicationInstanceId)
        if (activePhotoComplicationIds.isEmpty()) {
            stopRefreshLoop()
        }
    }

    override fun onDestroy() {
        stopRefreshLoop()
        super.onDestroy()
    }

    private fun startRefreshLoopIfNeeded() {
        if (tickerRunning || activePhotoComplicationIds.isEmpty()) {
            return
        }

        tickerRunning = true
        registerCalendarObserverIfNeeded()
        ComplicationWatchdogScheduler.start(this)

        mainHandler.removeCallbacks(minuteTicker)
        forceUpdateNow(this)
        val now = System.currentTimeMillis()
        val firstDelay = TICK_MS - (now % TICK_MS)
        mainHandler.postDelayed(minuteTicker, firstDelay)
    }

    private fun stopRefreshLoop() {
        tickerRunning = false
        mainHandler.removeCallbacks(minuteTicker)
        mainHandler.removeCallbacks(calendarChangeRefreshRunnable)
        ComplicationWatchdogScheduler.stop(this)
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
            val dayMillis = dayBounds.endMillis - dayBounds.startMillis
            val yesterdayStart = dayBounds.startMillis - dayMillis
            val tomorrowEnd = dayBounds.endMillis + dayMillis

            val raw = loadWearableEventsRaw(yesterdayStart, tomorrowEnd)
            val todayRaw = loadWearableEventsRaw(dayBounds.startMillis, dayBounds.endMillis)
            val events24h = raw.events.filter { overlapsRange(it, now, dayEnd) }
            val accountSummary = raw.accountHint?.takeIf { it.isNotBlank() } ?: "unavailable"
            val repeatedDailyEventIds = findRepeatedEventIds(raw.events)
            val todayEventsWithRecurrence = todayRaw.events.map { event ->
                if (event.eventId in repeatedDailyEventIds) {
                    event.copy(isDailyRepeated = true)
                } else {
                    event
                }
            }

            val detail =
                "src:wearable 24h:${events24h.size}  3d:${raw.events.size}  raw:${raw.rawCount}"

            CalendarProbeResult(
                status = "CALENDAR ACCESS OK",
                detail = detail,
                account = "Account: $accountSummary",
                sync = "sync: wearable managed",
                dayEvents = todayEventsWithRecurrence,
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

            val eventIdIndex = firstExistingColumnIndex(cursor, listOf("event_id", "_id"))
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
                        eventId = getLongCompat(cursor, eventIdIndex),
                        startMillis = start,
                        endMillis = end,
                        title = if (titleIndex >= 0) cursor.getString(titleIndex) else null,
                        isDailyRepeated = false,
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

    private fun findRepeatedEventIds(events: List<CalendarEventStub>): Set<Long> {
        val buckets = mutableMapOf<Long, MutableSet<Long>>()
        for (event in events) {
            if (event.eventId <= 0L) {
                continue
            }

            val dayId = localDayId(event.startMillis)
            buckets.getOrPut(event.eventId) { mutableSetOf() }.add(dayId)
        }

        return buckets
            .filterValues { distinctDays -> distinctDays.size >= 2 }
            .keys
    }

    private fun localDayId(epochMillis: Long): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = epochMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
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
        val showRecurringLabels = WatchSettingsStore.shouldShowRecurringLabels(this)
        val hidePastEventLabels = WatchSettingsStore.hidePastEventLabels(this)
        val use24HourTime = WatchSettingsStore.use24HourTime(this)
        val nowMillis = System.currentTimeMillis()

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
                val centerY: Float
            )

            data class RenderedSegment(
                val startMillis: Long,
                val endMillis: Long,
                val startDegrees: Float,
                val color: Int
            )

            data class ClippedEvent(
                val eventIndex: Int,
                val event: CalendarEventStub,
                val startMillis: Long,
                val endMillis: Long,
                val startDegrees: Float,
                val sweepDegrees: Float
            )

            data class EventSpan(
                val startMillis: Long,
                val endMillis: Long,
                val overlapDepth: Int
            )

            data class EventLabelPlan(
                val isDailyRepeated: Boolean,
                val eventPriority: Float,
                val candidates: List<LabelCandidate>
            )

            data class TimelineSegment(
                val startMillis: Long,
                val endMillis: Long,
                val startDegrees: Float,
                val sweepDegrees: Float,
                val activeEvents: List<ClippedEvent>
            )

            val renderedSegments = mutableListOf<RenderedSegment>()
            val clippedEvents = mutableListOf<ClippedEvent>()
            val eventSpansByIndex = mutableMapOf<Int, MutableList<EventSpan>>()
            val ringStrokeWidth = 14f
            val ringInnerRadius = ringRadius - (ringStrokeWidth / 2f)

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

                clippedEvents.add(
                    ClippedEvent(
                        eventIndex = clippedEvents.size,
                        event = event,
                        startMillis = clippedStart,
                        endMillis = clippedEnd,
                        startDegrees = startDegrees,
                        sweepDegrees = sweepDegrees
                    )
                )
                renderedSegments.add(
                    RenderedSegment(
                        startMillis = clippedStart,
                        endMillis = clippedEnd,
                        startDegrees = startDegrees,
                        color = event.color
                    )
                )
            }

            if (clippedEvents.isNotEmpty()) {
                val boundaries = mutableSetOf(probe.dayStartMillis, probe.dayEndMillis)
                for (event in clippedEvents) {
                    boundaries.add(event.startMillis)
                    boundaries.add(event.endMillis)
                }

                val timelineSegments = mutableListOf<TimelineSegment>()
                val sortedBoundaries = boundaries.sorted()
                for (i in 0 until sortedBoundaries.lastIndex) {
                    val segmentStart = sortedBoundaries[i]
                    val segmentEnd = sortedBoundaries[i + 1]
                    if (segmentEnd <= segmentStart) {
                        continue
                    }

                    val activeEvents = clippedEvents
                        .filter { it.startMillis < segmentEnd && it.endMillis > segmentStart }
                    if (activeEvents.isEmpty()) {
                        continue
                    }
                    val overlapDepth = activeEvents.size

                    val segmentStartFraction =
                        (segmentStart - probe.dayStartMillis).toFloat() / dayDuration.toFloat()
                    val segmentEndFraction =
                        (segmentEnd - probe.dayStartMillis).toFloat() / dayDuration.toFloat()
                    val segmentStartDegrees = -90f + (segmentStartFraction * 360f)
                    val segmentSweepDegrees = (segmentEndFraction - segmentStartFraction) * 360f
                    if (segmentSweepDegrees <= 0f) {
                        continue
                    }

                    timelineSegments.add(
                        TimelineSegment(
                            startMillis = segmentStart,
                            endMillis = segmentEnd,
                            startDegrees = segmentStartDegrees,
                            sweepDegrees = segmentSweepDegrees,
                            activeEvents = activeEvents
                        )
                    )

                    for (activeEvent in activeEvents) {
                        val spans = eventSpansByIndex.getOrPut(activeEvent.eventIndex) { mutableListOf() }
                        val lastSpan = spans.lastOrNull()
                        if (lastSpan != null &&
                            lastSpan.endMillis == segmentStart &&
                            lastSpan.overlapDepth == overlapDepth
                        ) {
                            spans[spans.lastIndex] = lastSpan.copy(endMillis = segmentEnd)
                        } else {
                            spans.add(
                                EventSpan(
                                    startMillis = segmentStart,
                                    endMillis = segmentEnd,
                                    overlapDepth = overlapDepth
                                )
                            )
                        }
                    }
                }

                var segmentIndex = 0
                while (segmentIndex < timelineSegments.size) {
                    val segment = timelineSegments[segmentIndex]
                    if (segment.activeEvents.size <= 1) {
                        val singleEvent = segment.activeEvents.first()
                        arcPaint.color = singleEvent.event.color
                        arcPaint.strokeWidth = ringStrokeWidth
                        canvas.drawArc(
                            ringRect,
                            segment.startDegrees,
                            segment.sweepDegrees,
                            false,
                            arcPaint
                        )
                        segmentIndex += 1
                        continue
                    }

                    var clusterEndIndex = segmentIndex + 1
                    while (clusterEndIndex < timelineSegments.size) {
                        val previous = timelineSegments[clusterEndIndex - 1]
                        val next = timelineSegments[clusterEndIndex]
                        if (next.activeEvents.size <= 1 || next.startMillis != previous.endMillis) {
                            break
                        }
                        clusterEndIndex += 1
                    }

                    val overlapCluster = timelineSegments.subList(segmentIndex, clusterEndIndex)
                    val clusterDepth = overlapCluster.maxOf { it.activeEvents.size }
                    var previousLaneEvents: IntArray? = null

                    for (clusterSegment in overlapCluster) {
                        val laneEvents = IntArray(clusterDepth) { -1 }
                        val activeByIndex = clusterSegment.activeEvents.associateBy { it.eventIndex }

                        val containmentCountByEventIndex = clusterSegment.activeEvents.associate { candidate ->
                            val containingCount = clusterSegment.activeEvents.count { other ->
                                other.eventIndex != candidate.eventIndex &&
                                    other.startMillis <= candidate.startMillis &&
                                    other.endMillis >= candidate.endMillis
                            }
                            candidate.eventIndex to containingCount
                        }
                        val durationByEventIndex = clusterSegment.activeEvents.associate {
                            it.eventIndex to (it.endMillis - it.startMillis)
                        }
                        val startByEventIndex = clusterSegment.activeEvents.associate {
                            it.eventIndex to it.startMillis
                        }

                        fun insideCompare(a: Int, b: Int): Int {
                            val containmentA = containmentCountByEventIndex[a] ?: 0
                            val containmentB = containmentCountByEventIndex[b] ?: 0
                            if (containmentA != containmentB) {
                                return containmentA.compareTo(containmentB)
                            }

                            val durationA = durationByEventIndex[a] ?: Long.MAX_VALUE
                            val durationB = durationByEventIndex[b] ?: Long.MAX_VALUE
                            if (durationA != durationB) {
                                return durationB.compareTo(durationA)
                            }

                            val startA = startByEventIndex[a] ?: Long.MAX_VALUE
                            val startB = startByEventIndex[b] ?: Long.MAX_VALUE
                            if (startA != startB) {
                                return startB.compareTo(startA)
                            }

                            return b.compareTo(a)
                        }

                        if (previousLaneEvents != null) {
                            for (lane in 0 until clusterDepth) {
                                val previousEventIndex = previousLaneEvents[lane]
                                if (previousEventIndex >= 0 && activeByIndex.containsKey(previousEventIndex)) {
                                    laneEvents[lane] = previousEventIndex
                                }
                            }
                        }

                        fun laneCounts(): MutableMap<Int, Int> {
                            val counts = mutableMapOf<Int, Int>()
                            for (eventIndex in laneEvents) {
                                if (eventIndex >= 0) {
                                    counts[eventIndex] = (counts[eventIndex] ?: 0) + 1
                                }
                            }
                            return counts
                        }

                        val activeEventIndexes = clusterSegment.activeEvents.map { it.eventIndex }
                        val eventIndexesMissingLane = {
                            val counts = laneCounts()
                            activeEventIndexes.filter { (counts[it] ?: 0) == 0 }
                                .sortedWith { a, b ->
                                    -insideCompare(a, b)
                                }
                        }

                        for (missingEventIndex in eventIndexesMissingLane()) {
                            var targetLane = laneEvents.indexOfFirst { it < 0 }
                            if (targetLane < 0) {
                                val counts = laneCounts()
                                val donorEventIndex = activeEventIndexes
                                    .filter { (counts[it] ?: 0) > 1 }
                                    .minWithOrNull { a, b ->
                                        insideCompare(a, b)
                                    }

                                if (donorEventIndex != null) {
                                    val donorLanes = laneEvents.indices.filter { laneEvents[it] == donorEventIndex }
                                    val missingHigherPriority = insideCompare(missingEventIndex, donorEventIndex) > 0
                                    targetLane = if (missingHigherPriority) {
                                        donorLanes.minOrNull() ?: -1
                                    } else {
                                        donorLanes.maxOrNull() ?: -1
                                    }
                                }
                            }

                            if (targetLane >= 0) {
                                laneEvents[targetLane] = missingEventIndex
                            }
                        }

                        if (eventIndexesMissingLane().isNotEmpty()) {
                            val orderedEventIndexes = activeEventIndexes.sortedWith { a, b ->
                                -insideCompare(a, b)
                            }
                            for (lane in 0 until clusterDepth) {
                                laneEvents[lane] = orderedEventIndexes[lane.coerceAtMost(orderedEventIndexes.lastIndex)]
                            }
                        }

                        for (lane in 0 until clusterDepth) {
                            if (laneEvents[lane] >= 0) {
                                continue
                            }

                            var left = lane - 1
                            while (left >= 0 && laneEvents[left] < 0) {
                                left -= 1
                            }
                            var right = lane + 1
                            while (right < clusterDepth && laneEvents[right] < 0) {
                                right += 1
                            }

                            val chosenEventIndex = when {
                                left < 0 && right < clusterDepth -> laneEvents[right]
                                right >= clusterDepth && left >= 0 -> laneEvents[left]
                                left >= 0 && right < clusterDepth -> {
                                    val leftDistance = lane - left
                                    val rightDistance = right - lane
                                    if (leftDistance <= rightDistance) laneEvents[left] else laneEvents[right]
                                }

                                else -> clusterSegment.activeEvents.first().eventIndex
                            }
                            laneEvents[lane] = chosenEventIndex
                        }

                        var runStart = 0
                        while (runStart < clusterDepth) {
                            val eventIndex = laneEvents[runStart]
                            var runEnd = runStart + 1
                            while (runEnd < clusterDepth && laneEvents[runEnd] == eventIndex) {
                                runEnd += 1
                            }

                            val event = activeByIndex[eventIndex]
                            if (event != null) {
                                val runInner = ringInnerRadius + (ringStrokeWidth * (runStart / clusterDepth.toFloat()))
                                val runOuter = ringInnerRadius + (ringStrokeWidth * (runEnd / clusterDepth.toFloat()))
                                val runRadius = (runInner + runOuter) / 2f
                                val runRect = RectF(
                                    center - runRadius,
                                    center - runRadius,
                                    center + runRadius,
                                    center + runRadius
                                )

                                arcPaint.color = event.event.color
                                arcPaint.strokeWidth = runOuter - runInner
                                canvas.drawArc(
                                    runRect,
                                    clusterSegment.startDegrees,
                                    clusterSegment.sweepDegrees,
                                    false,
                                    arcPaint
                                )
                            }

                            runStart = runEnd
                        }

                        previousLaneEvents = laneEvents
                    }

                    segmentIndex = clusterEndIndex
                }
            }

            val eventLabelPlans = mutableListOf<EventLabelPlan>()

            for (clippedEvent in clippedEvents) {
                val event = clippedEvent.event
                val title = event.title?.trim().orEmpty()
                if (title.isBlank()) {
                    continue
                }
                if (!showRecurringLabels && event.isDailyRepeated) {
                    continue
                }
                if (hidePastEventLabels && event.endMillis <= nowMillis) {
                    continue
                }

                val label = fitLabelToWidth(title, labelPaint, maxLabelWidth)
                if (label.isBlank()) {
                    continue
                }
                val textWidth = labelPaint.measureText(label)

                val eventMidMillis = clippedEvent.startMillis + ((clippedEvent.endMillis - clippedEvent.startMillis) / 2L)
                val spanCandidates = eventSpansByIndex[clippedEvent.eventIndex]
                    .orEmpty()
                    .ifEmpty {
                        listOf(
                            EventSpan(
                                startMillis = clippedEvent.startMillis,
                                endMillis = clippedEvent.endMillis,
                                overlapDepth = 1
                            )
                        )
                    }
                    .sortedWith(
                        compareBy<EventSpan> { it.overlapDepth }
                            .thenByDescending { it.endMillis - it.startMillis }
                            .thenBy {
                                abs(
                                    (it.startMillis + ((it.endMillis - it.startMillis) / 2L)) -
                                        eventMidMillis
                                )
                            }
                    )

                val labelCandidatesForEvent = mutableListOf<LabelCandidate>()
                for (span in spanCandidates.take(5)) {
                    val spanMidMillis = span.startMillis + ((span.endMillis - span.startMillis) / 2L)
                    val midFraction =
                        (spanMidMillis - probe.dayStartMillis).toFloat() / dayDuration.toFloat()
                    val midDegrees = -90f + (midFraction * 360f)
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

                    labelCandidatesForEvent.add(
                        LabelCandidate(
                            label = label,
                            anchorX = anchorX,
                            anchorY = anchorY,
                            rotation = labelRotation,
                            textStartX = textStartX,
                            textBaselineY = textBaselineY,
                            centerX = textCenterX,
                            centerY = textCenterY
                        )
                    )
                }

                if (labelCandidatesForEvent.isNotEmpty()) {
                    eventLabelPlans.add(
                        EventLabelPlan(
                            isDailyRepeated = event.isDailyRepeated,
                            eventPriority = clippedEvent.sweepDegrees,
                            candidates = labelCandidatesForEvent
                        )
                    )
                }
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
            for (eventPlan in eventLabelPlans.sortedWith(
                compareBy<EventLabelPlan> { it.isDailyRepeated }
                    .thenByDescending { it.eventPriority }
            )) {
                for (candidate in eventPlan.candidates) {
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
                    break
                }
            }

            if (dayDuration > 0L) {
                val nowFraction = ((nowMillis - probe.dayStartMillis).toDouble() / dayDuration.toDouble())
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
        val minute = nowCal.get(java.util.Calendar.MINUTE)
        val timeText: String
        val amPmText: String?
        if (use24HourTime) {
            val hour24 = nowCal.get(java.util.Calendar.HOUR_OF_DAY)
            timeText = String.format(Locale.getDefault(), "%02d:%02d", hour24, minute)
            amPmText = null
        } else {
            val hour12Raw = nowCal.get(java.util.Calendar.HOUR)
            val hour12 = if (hour12Raw == 0) 12 else hour12Raw
            timeText = String.format(Locale.getDefault(), "%d:%02d", hour12, minute)
            amPmText = if (nowCal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        }

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
        if (amPmText != null) {
            canvas.drawText(amPmText, center, center + 35f, amPmPaint)
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
    val eventId: Long,
    val startMillis: Long,
    val endMillis: Long,
    val title: String?,
    val isDailyRepeated: Boolean,
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
