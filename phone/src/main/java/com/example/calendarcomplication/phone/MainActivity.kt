package com.example.calendarcomplication.phone

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.compose.ui.res.stringResource
import com.example.calendarcomplication.core.render.CalendarPreviewRenderer
import com.example.calendarcomplication.core.render.CalendarRenderEvent
import com.example.calendarcomplication.core.render.CalendarRenderProbe
import com.example.calendarcomplication.core.settings.CalendarSettings
import com.example.calendarcomplication.core.settings.ComplicationSettingsPanel
import com.example.calendarcomplication.core.settings.CalendarSettingsStore
import com.example.calendarcomplication.core.sync.SettingsSyncContract
import com.example.calendarcomplication.phone.sync.CalendarSyncScheduler
import com.example.calendarcomplication.phone.sync.CalendarSyncTransmitter
import com.example.calendarcomplication.phone.sync.SettingsSyncTransmitter
import java.util.Calendar

class MainActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var previewView: ImageView
    private val settingsState = mutableStateOf(
        CalendarSettings(
            showRecurringLabels = false,
            use24HourTime = false,
            hidePastEventLabels = true
        )
    )
    private var isSettingsReceiverRegistered = false

    private val settingsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SettingsSyncContract.ACTION_SETTINGS_UPDATED) {
                return
            }
            settingsState.value = CalendarSettingsStore.load(this@MainActivity)
            if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                refreshPreview()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.BLACK

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val container = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (isLandscape) {
                isBaselineAligned = false
            }
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.BLACK)
        }

        previewView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 6, 0, if (isLandscape) 0 else 16)
        }

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 8)
            visibility = View.GONE
        }

        val settingsView = ComposeView(this).apply {
            layoutParams = if (isLandscape) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        }

        if (isLandscape) {
            val previewColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            previewView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0,
                1f
            )
            previewColumn.addView(previewView)
            previewColumn.addView(statusView)
            container.addView(previewColumn)
            container.addView(settingsView)
        } else {
            container.addView(previewView)
            container.addView(statusView)
            container.addView(settingsView)
        }
        setContentView(container)

        settingsView.setContent {
            MaterialTheme {
                ComplicationSettingsPanel(
                    settings = settingsState.value,
                    title = stringResource(R.string.settings_title),
                    recurringTitle = stringResource(R.string.settings_recurring_label_title),
                    recurringSubtitle = stringResource(R.string.settings_recurring_label_subtitle),
                    twentyFourHourTitle = stringResource(R.string.settings_24_hour_title),
                    twentyFourHourSubtitle = stringResource(R.string.settings_24_hour_subtitle),
                    hidePastTitle = stringResource(R.string.settings_hide_past_labels_title),
                    hidePastSubtitle = stringResource(R.string.settings_hide_past_labels_subtitle),
                    onSettingsChange = { newSettings -> applySettingsAndSync(newSettings) }
                )
            }
        }

        CalendarSyncScheduler.schedulePeriodic(this)
        settingsState.value = CalendarSettingsStore.load(this)

        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            pushSettingsToWatch("phone")
            CalendarSyncScheduler.triggerImmediate(this)
            refreshPreview()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQUEST_CALENDAR_PERMISSION)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isSettingsReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                settingsUpdatedReceiver,
                IntentFilter(SettingsSyncContract.ACTION_SETTINGS_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isSettingsReceiverRegistered = true
        }
        settingsState.value = CalendarSettingsStore.load(this)
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            refreshPreview()
        }
    }

    override fun onPause() {
        if (isSettingsReceiverRegistered) {
            runCatching { unregisterReceiver(settingsUpdatedReceiver) }
            isSettingsReceiverRegistered = false
        }
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALENDAR_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            pushSettingsToWatch("phone")
            CalendarSyncScheduler.triggerImmediate(this)
            refreshPreview()
        }
    }

    private fun applySettingsAndSync(newSettings: CalendarSettings) {
        settingsState.value = newSettings
        CalendarSettingsStore.save(this, newSettings)
        pushSettingsToWatch("phone")
        CalendarSyncScheduler.triggerImmediate(this)
        refreshPreview()
    }

    private fun pushSettingsToWatch(source: String) {
        SettingsSyncTransmitter.push(this, settingsState.value, source = source)
    }

    private fun refreshPreview() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val now = System.currentTimeMillis()
        val dayBounds = localDayBounds(now)
        val dayMillis = dayBounds.second - dayBounds.first
        val snapshot = CalendarSyncTransmitter.loadSnapshot(
            context = this,
            startMillis = dayBounds.first - dayMillis,
            endMillis = dayBounds.second + dayMillis
        )

        val repeatedIds = findRepeatedEventIds(snapshot.events)
        val dayEvents = snapshot.events
            .filter { it.endMillis >= dayBounds.first && it.startMillis <= dayBounds.second }
            .map { event ->
                if (event.eventId in repeatedIds) {
                    event.copy(isDailyRepeated = true)
                } else {
                    event
                }
            }

        val bitmap = CalendarPreviewRenderer.render(
            settings = settingsState.value,
            probe = CalendarRenderProbe(
                dayEvents = dayEvents,
                dayStartMillis = dayBounds.first,
                dayEndMillis = dayBounds.second,
                nowMillis = now
            )
        )
        previewView.setImageBitmap(bitmap)
    }

    private fun findRepeatedEventIds(events: List<CalendarRenderEvent>): Set<Long> {
        val buckets = mutableMapOf<Long, MutableSet<Long>>()
        for (event in events) {
            if (event.eventId <= 0L) {
                continue
            }
            buckets.getOrPut(event.eventId) { mutableSetOf() }.add(localDayId(event.startMillis))
        }
        return buckets.filterValues { it.size >= 2 }.keys
    }

    private fun localDayId(epochMillis: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = epochMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun localDayBounds(nowMillis: Long): Pair<Long, Long> {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        return start to calendar.timeInMillis
    }

    companion object {
        private const val REQUEST_CALENDAR_PERMISSION = 1001
    }
}
