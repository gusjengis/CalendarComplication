package com.example.calendarcomplication.complication

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.EmptyComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.util.Calendar

class MainComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.PHOTO_IMAGE) return null

        val bitmap = generateBitmap("PREVIEW")
        val icon = Icon.createWithBitmap(bitmap)

        return PhotoImageComplicationData.Builder(
            photoImage = icon,
            contentDescription = PlainComplicationText.Builder("Preview calendar image").build()
        ).build()
    }

    override suspend fun onComplicationRequest(
        request: ComplicationRequest
    ): ComplicationData {
        return when (request.complicationType) {
            ComplicationType.PHOTO_IMAGE -> {
                val label = currentDayLabel()
                val bitmap = generateBitmap(label)
                val icon = Icon.createWithBitmap(bitmap)

                PhotoImageComplicationData.Builder(
                    photoImage = icon,
                    contentDescription = PlainComplicationText.Builder("Calendar background").build()
                ).build()
            }

            else -> EmptyComplicationData()
        }
    }

    /**
     * Generates a simple 450x450 bitmap with:
     * - A colored background
     * - A circle in the center
     * - A big label (e.g. day of week) rendered in the middle
     */
    private fun generateBitmap(label: String): Bitmap {
        val width = 450
        val height = 450

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background paint (dark-ish)
        val bgPaint = Paint().apply {
            isAntiAlias = true
            color = Color.rgb(20, 30, 60)
            style = Paint.Style.FILL
        }

        // Accent circle paint
        val circlePaint = Paint().apply {
            isAntiAlias = true
            color = Color.rgb(80, 160, 255)
            style = Paint.Style.FILL
        }

        // Text paint
        val textPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 120f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        // Fill background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Draw central circle
        val cx = width / 2f
        val cy = height / 2f
        val radius = width * 0.35f
        canvas.drawCircle(cx, cy, radius, circlePaint)

        // Draw label text vertically centered
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val textHeight = textBounds.height()
        val textY = cy + textHeight / 2f

        canvas.drawText(label, cx, textY, textPaint)

        return bitmap
    }

    private fun currentDayLabel(): String {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "Sun"
            Calendar.MONDAY -> "Mon"
            Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"
            Calendar.THURSDAY -> "Thu"
            Calendar.FRIDAY -> "Fri"
            Calendar.SATURDAY -> "Sat"
            else -> "???"
        }
    }
}
