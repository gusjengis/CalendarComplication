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
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
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

        val probe = probeCalendarDataAccess()
        Log.d(TAG, "Calendar probe: ${probe.status} (${probe.detail})")

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
                sync = "sync: wearable managed"
            )
        }

        return try {
            val now = System.currentTimeMillis()
            val dayEnd = now + 24L * 60L * 60L * 1000L
            val weekEnd = now + 7L * 24L * 60L * 60L * 1000L

            val raw = loadWearableEventsRaw(now, weekEnd)
            val events24h = raw.events.filter { overlapsRange(it, now, dayEnd) }
            val accountSummary = raw.accountHint?.takeIf { it.isNotBlank() } ?: "unavailable"

            val detail =
                "src:wearable 24h:${events24h.size}  7d:${raw.events.size}  raw:${raw.rawCount}"

            CalendarProbeResult(
                status = "CALENDAR ACCESS OK",
                detail = detail,
                account = "Account: $accountSummary",
                sync = "sync: wearable managed"
            )
        } catch (t: Throwable) {
            CalendarProbeResult(
                status = "CALENDAR READ ERROR",
                detail = t.javaClass.simpleName,
                account = "Account: unavailable",
                sync = "sync: wearable managed"
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
                        endMillis = end
                    )
                )
            }
        }

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
    val startMillis: Long,
    val endMillis: Long
)

private data class WearableEventsRaw(
    val events: List<CalendarEventStub>,
    val rawCount: Int,
    val accountHint: String?
)
