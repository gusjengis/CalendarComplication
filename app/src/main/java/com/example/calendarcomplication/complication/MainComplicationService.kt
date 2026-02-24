package com.example.calendarcomplication.complication

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.EmptyComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale

class MainComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val TAG = "MainComplicationService"
        private const val IMAGE_SIZE = 450

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

    private val ticker = object : Runnable {
        override fun run() {
            if (!tickerRunning) {
                return
            }

            forceUpdateNow(this@MainComplicationService)
            val now = System.currentTimeMillis()
            val nextTickDelay = 1000L - (now % 1000L)
            mainHandler.postDelayed(this, nextTickDelay)
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.PHOTO_IMAGE) {
            Log.w(TAG, "Unsupported preview type requested: $type")
            return null
        }

        val icon = Icon.createWithBitmap(generateDebugBitmap("PREVIEW"))

        return PhotoImageComplicationData.Builder(
            photoImage = icon,
            contentDescription = PlainComplicationText.Builder("Preview debug image").build()
        ).build()
    }

    override suspend fun onComplicationRequest(
        request: ComplicationRequest
    ): ComplicationData {
        Log.d(TAG, "Complication request id=${request.complicationInstanceId} type=${request.complicationType}")

        return when (request.complicationType) {
            ComplicationType.PHOTO_IMAGE -> {
                activePhotoComplicationIds.add(request.complicationInstanceId)
                startTickerIfNeeded()

                val bitmap = generateDebugBitmap(currentDayLabel())
                val icon = Icon.createWithBitmap(bitmap)

                PhotoImageComplicationData.Builder(
                    photoImage = icon,
                    contentDescription = PlainComplicationText.Builder("Generated debug photo image").build()
                ).build()
            }

            else -> {
                Log.w(TAG, "Unsupported request type=${request.complicationType}, returning EmptyComplicationData")
                EmptyComplicationData()
            }
        }
    }

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        Log.d(TAG, "Activated id=$complicationInstanceId type=$type")
        if (type == ComplicationType.PHOTO_IMAGE) {
            activePhotoComplicationIds.add(complicationInstanceId)
            startTickerIfNeeded()
        }
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        super.onComplicationDeactivated(complicationInstanceId)
        Log.d(TAG, "Deactivated id=$complicationInstanceId")
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
        mainHandler.removeCallbacks(ticker)
        ticker.run()
    }

    private fun stopTicker() {
        tickerRunning = false
        mainHandler.removeCallbacks(ticker)
    }

    private fun generateDebugBitmap(label: String): Bitmap {
        val bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val sizeF = IMAGE_SIZE.toFloat()

        val gradient = LinearGradient(
            0f,
            0f,
            sizeF,
            sizeF,
            Color.rgb(16, 20, 32),
            Color.rgb(26, 58, 98),
            Shader.TileMode.CLAMP
        )
        val backgroundPaint = Paint().apply {
            shader = gradient
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, sizeF, sizeF, backgroundPaint)

        val stripePaint = Paint().apply {
            isAntiAlias = true
            color = Color.argb(80, 255, 255, 255)
            strokeWidth = 8f
        }
        var x = -sizeF
        while (x < sizeF * 2f) {
            canvas.drawLine(x, 0f, x - sizeF, sizeF, stripePaint)
            x += 45f
        }

        val cardPaint = Paint().apply {
            isAntiAlias = true
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val cardRect = RectF(40f, 140f, sizeF - 40f, sizeF - 120f)
        canvas.drawRoundRect(cardRect, 24f, 24f, cardPaint)

        val dayPaint = Paint().apply {
            isAntiAlias = true
            color = Color.rgb(214, 230, 255)
            textSize = 54f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val timePaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 84f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
        }

        val now = Calendar.getInstance()
        val timeText = String.format(
            Locale.US,
            "%02d:%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            now.get(Calendar.SECOND)
        )
        val dateText = String.format(
            Locale.US,
            "%1\$ta %1\$tb %1\$td",
            Date(now.timeInMillis)
        ).uppercase(Locale.US)

        canvas.drawText(timeText, sizeF / 2f, 250f, timePaint)
        canvas.drawText(label, sizeF / 2f, 315f, dayPaint)
        canvas.drawText(dateText, sizeF / 2f, 365f, dayPaint)

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
