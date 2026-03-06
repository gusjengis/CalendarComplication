package com.example.calendarcomplication.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.example.calendarcomplication.core.settings.CalendarSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object CalendarPreviewRenderer {
    fun render(
        settings: CalendarSettings,
        probe: CalendarRenderProbe,
        sizePx: Int = 450
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val sizeF = sizePx.toFloat()
        val center = sizeF / 2f
        val showRecurringLabels = settings.showRecurringLabels
        val hidePastEventLabels = settings.hidePastEventLabels
        val use24HourTime = settings.use24HourTime
        val nowMillis = probe.nowMillis
        var eventTimingText: String? = null
        var eventTimingColor = Color.rgb(172, 196, 255)

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
                val collisionQuad: FloatArray
            )

            data class RenderedSegment(
                val eventIndex: Int,
                val startMillis: Long,
                val endMillis: Long,
                val startDegrees: Float,
                val color: Int
            )

            data class ClippedEvent(
                val eventIndex: Int,
                val event: CalendarRenderEvent,
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
                        eventIndex = clippedEvents.lastIndex,
                        startMillis = clippedStart,
                        endMillis = clippedEnd,
                        startDegrees = startDegrees,
                        color = event.color
                    )
                )
            }

            val resolvedColorByEventIndex = mutableMapOf<Int, Int>()
            if (clippedEvents.isNotEmpty()) {
                val neighborToleranceMs = 60_000L
                val groupedByBaseColor = clippedEvents.groupBy { it.event.color }

                fun colorsForBase(baseColor: Int): List<Int> {
                    val hsv = FloatArray(3)
                    Color.colorToHSV(baseColor, hsv)
                    val satBase = hsv[1]
                    val valueBase = hsv[2]

                    data class Offset(val satDelta: Float, val valueDelta: Float)
                    val offsets = listOf(
                        Offset(0f, 0f),
                        Offset(0.16f, -0.24f),
                        Offset(-0.14f, 0.22f),
                        Offset(0.22f, -0.36f),
                        Offset(-0.2f, 0.34f),
                        Offset(0.08f, -0.14f),
                        Offset(-0.08f, 0.14f)
                    )

                    return offsets.map { offset ->
                        val variantHsv = floatArrayOf(
                            hsv[0],
                            (satBase + offset.satDelta).coerceIn(0.28f, 1f),
                            (valueBase + offset.valueDelta).coerceIn(0.18f, 1f)
                        )
                        Color.HSVToColor(variantHsv)
                    }
                }

                for ((baseColor, groupEvents) in groupedByBaseColor) {
                    val eventIndexes = groupEvents.map { it.eventIndex }
                    val neighbors = eventIndexes.associateWith { mutableSetOf<Int>() }

                    for (i in 0 until groupEvents.size) {
                        val first = groupEvents[i]
                        for (j in i + 1 until groupEvents.size) {
                            val second = groupEvents[j]
                            val overlaps = first.startMillis < second.endMillis &&
                                second.startMillis < first.endMillis
                            val touches = abs(first.endMillis - second.startMillis) <= neighborToleranceMs ||
                                abs(second.endMillis - first.startMillis) <= neighborToleranceMs
                            if (overlaps || touches) {
                                neighbors[first.eventIndex]?.add(second.eventIndex)
                                neighbors[second.eventIndex]?.add(first.eventIndex)
                            }
                        }
                    }

                    val variants = colorsForBase(baseColor)
                    val chosenVariantByEvent = mutableMapOf<Int, Int>()
                    val orderedEventIndexes = groupEvents
                        .sortedWith(
                            compareByDescending<ClippedEvent> { neighbors[it.eventIndex]?.size ?: 0 }
                                .thenBy { it.startMillis }
                                .thenBy { it.endMillis }
                                .thenBy { it.event.eventId }
                        )
                        .map { it.eventIndex }

                    for (eventIndex in orderedEventIndexes) {
                        val usedByNeighbors = neighbors[eventIndex]
                            .orEmpty()
                            .mapNotNull { chosenVariantByEvent[it] }
                            .toSet()

                        val preferredVariant = variants.indices.firstOrNull { it !in usedByNeighbors }
                        val chosenVariant = preferredVariant ?: ((eventIndex % variants.size) + variants.size) % variants.size
                        chosenVariantByEvent[eventIndex] = chosenVariant
                    }

                    for ((eventIndex, variantIndex) in chosenVariantByEvent) {
                        resolvedColorByEventIndex[eventIndex] = variants[variantIndex]
                    }
                }

                val activeEvent = clippedEvents
                    .filter { it.startMillis <= nowMillis && nowMillis < it.endMillis }
                    .minByOrNull { it.endMillis }
                if (activeEvent != null) {
                    val remainingMillis =
                        (activeEvent.event.endMillis - nowMillis).coerceAtLeast(0L)
                    eventTimingText = formatDurationCompact(remainingMillis)
                    eventTimingColor = resolvedColorByEventIndex[activeEvent.eventIndex]
                        ?: activeEvent.event.color
                } else {
                    val nextEvent = clippedEvents
                        .filter { it.startMillis > nowMillis }
                        .minByOrNull { it.startMillis }
                    if (nextEvent != null) {
                        val untilStartMillis = (nextEvent.startMillis - nowMillis).coerceAtLeast(0L)
                        eventTimingText = "in ${formatDurationCompact(untilStartMillis)}"
                        eventTimingColor = resolvedColorByEventIndex[nextEvent.eventIndex]
                            ?: nextEvent.event.color
                    }
                }
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
                        arcPaint.color =
                            resolvedColorByEventIndex[singleEvent.eventIndex] ?: singleEvent.event.color
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

                                arcPaint.color =
                                    resolvedColorByEventIndex[event.eventIndex] ?: event.event.color
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
                val glyphBounds = Rect().apply {
                    labelPaint.getTextBounds(label, 0, label.length, this)
                }

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
                    val rotationRadians = Math.toRadians(labelRotation.toDouble())
                    val fontMetrics = labelPaint.fontMetrics
                    val textBaselineY = -((fontMetrics.ascent + fontMetrics.descent) / 2f)

                    val collisionPadding = 0.5f
                    val localLeft = textStartX + glyphBounds.left - collisionPadding
                    val localRight = textStartX + glyphBounds.right + collisionPadding
                    val localTop = textBaselineY + glyphBounds.top - collisionPadding
                    val localBottom = textBaselineY + glyphBounds.bottom + collisionPadding
                    val rotationCos = cos(rotationRadians).toFloat()
                    val rotationSin = sin(rotationRadians).toFloat()

                    fun worldPoint(localX: Float, localY: Float): Pair<Float, Float> {
                        val worldX = anchorX + (localX * rotationCos) - (localY * rotationSin)
                        val worldY = anchorY + (localX * rotationSin) + (localY * rotationCos)
                        return worldX to worldY
                    }

                    val topLeft = worldPoint(localLeft, localTop)
                    val topRight = worldPoint(localRight, localTop)
                    val bottomRight = worldPoint(localRight, localBottom)
                    val bottomLeft = worldPoint(localLeft, localBottom)
                    val collisionQuad = floatArrayOf(
                        topLeft.first,
                        topLeft.second,
                        topRight.first,
                        topRight.second,
                        bottomRight.first,
                        bottomRight.second,
                        bottomLeft.first,
                        bottomLeft.second
                    )

                    labelCandidatesForEvent.add(
                        LabelCandidate(
                            label = label,
                            anchorX = anchorX,
                            anchorY = anchorY,
                            rotation = labelRotation,
                            textStartX = textStartX,
                            textBaselineY = textBaselineY,
                            collisionQuad = collisionQuad
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
                val previousColor = resolvedColorByEventIndex[previous.eventIndex] ?: previous.color
                val currentColor = resolvedColorByEventIndex[current.eventIndex] ?: current.color
                val isSameColor = previousColor == currentColor
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

            fun projectQuadOnAxis(quad: FloatArray, axisX: Float, axisY: Float): Pair<Float, Float> {
                val firstProjection = (quad[0] * axisX) + (quad[1] * axisY)
                var minProjection = firstProjection
                var maxProjection = firstProjection
                var index = 2
                while (index < quad.size) {
                    val projection = (quad[index] * axisX) + (quad[index + 1] * axisY)
                    if (projection < minProjection) {
                        minProjection = projection
                    }
                    if (projection > maxProjection) {
                        maxProjection = projection
                    }
                    index += 2
                }
                return minProjection to maxProjection
            }

            fun quadsOverlap(quadA: FloatArray, quadB: FloatArray): Boolean {
                fun separatedByAnyAxis(fromQuad: FloatArray): Boolean {
                    val separationEpsilon = 1.0f
                    var i = 0
                    while (i < fromQuad.size) {
                        val next = (i + 2) % fromQuad.size
                        val edgeX = fromQuad[next] - fromQuad[i]
                        val edgeY = fromQuad[next + 1] - fromQuad[i + 1]
                        val axisX = -edgeY
                        val axisY = edgeX

                        val projA = projectQuadOnAxis(quadA, axisX, axisY)
                        val projB = projectQuadOnAxis(quadB, axisX, axisY)
                        if (projA.second <= projB.first + separationEpsilon ||
                            projB.second <= projA.first + separationEpsilon
                        ) {
                            return true
                        }
                        i += 2
                    }
                    return false
                }

                if (separatedByAnyAxis(quadA)) {
                    return false
                }
                if (separatedByAnyAxis(quadB)) {
                    return false
                }
                return true
            }

            val placedLabelQuads = mutableListOf<FloatArray>()
            data class PlacedLabel(
                val candidate: LabelCandidate,
                val isDailyRepeated: Boolean
            )
            val placedLabels = mutableListOf<PlacedLabel>()

            fun placeLabelPlans(plans: List<EventLabelPlan>) {
                for (eventPlan in plans) {
                    for (candidate in eventPlan.candidates) {
                        val overlapsExisting = placedLabelQuads.any { existingQuad ->
                            quadsOverlap(candidate.collisionQuad, existingQuad)
                        }
                        if (overlapsExisting) {
                            continue
                        }

                        placedLabelQuads.add(candidate.collisionQuad)
                        placedLabels.add(
                            PlacedLabel(
                                candidate = candidate,
                                isDailyRepeated = eventPlan.isDailyRepeated
                            )
                        )
                        break
                    }
                }
            }

            val nonDailyPlans = eventLabelPlans
                .filter { !it.isDailyRepeated }
                .sortedByDescending { it.eventPriority }
            val dailyPlans = eventLabelPlans
                .filter { it.isDailyRepeated }
                .sortedByDescending { it.eventPriority }

            placeLabelPlans(nonDailyPlans)
            placeLabelPlans(dailyPlans)

            for (placed in placedLabels.sortedByDescending { it.isDailyRepeated }) {
                val candidate = placed.candidate
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
        if (use24HourTime) {
            val hour24 = nowCal.get(java.util.Calendar.HOUR_OF_DAY)
            timeText = String.format(Locale.getDefault(), "%02d:%02d", hour24, minute)
        } else {
            val hour12Raw = nowCal.get(java.util.Calendar.HOUR)
            val hour12 = if (hour12Raw == 0) 12 else hour12Raw
            timeText = String.format(Locale.getDefault(), "%d:%02d", hour12, minute)
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
        val eventTimingShadowPaint = Paint().apply {
            color = Color.argb(160, 0, 0, 0)
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val eventTimingPaint = Paint().apply {
            color = eventTimingColor
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        canvas.drawText(timeText, center + 1f, center + 16f, timeShadowPaint)
        canvas.drawText(timeText, center, center + 15f, timePaint)
        if (eventTimingText != null) {
            canvas.drawText(eventTimingText, center + 1f, center + 42f, eventTimingShadowPaint)
            canvas.drawText(eventTimingText, center, center + 41f, eventTimingPaint)
        }

        return bitmap
    }

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

    private fun formatDurationCompact(durationMillis: Long): String {
        val totalMinutes = (durationMillis / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
            hours > 0L -> "${hours}h"
            totalMinutes > 0L -> "${totalMinutes}m"
            else -> "<1m"
        }
    }
}
