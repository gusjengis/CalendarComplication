package com.example.calendarcomplication.phone

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.example.calendarcomplication.core.render.CalendarPreviewRenderer
import com.example.calendarcomplication.core.render.CalendarRenderEvent
import com.example.calendarcomplication.core.render.CalendarRenderProbe
import com.example.calendarcomplication.core.settings.CalendarSettings
import com.example.calendarcomplication.core.settings.CalendarSettingsStore
import com.example.calendarcomplication.core.sync.SettingsSyncContract
import com.example.calendarcomplication.phone.sync.CalendarSyncScheduler
import com.example.calendarcomplication.phone.sync.CalendarSyncTransmitter
import com.example.calendarcomplication.phone.sync.SettingsSyncTransmitter
import java.util.Calendar

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var previewView: ImageView
    private lateinit var previewHeaderView: TextView
    private lateinit var settingsHeaderView: TextView
    private lateinit var recurringSwitch: Switch
    private lateinit var use24HourSwitch: Switch
    private lateinit var hidePastSwitch: Switch

    private var settings: CalendarSettings = CalendarSettings(
        showRecurringLabels = false,
        use24HourTime = false,
        hidePastEventLabels = true
    )

    private val settingsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SettingsSyncContract.ACTION_SETTINGS_UPDATED) {
                return
            }
            settings = CalendarSettingsStore.load(this@MainActivity)
            bindSettingsUi()
            if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                refreshPreview()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)
        }

        previewHeaderView = TextView(this).apply {
            text = "Watch Face Preview"
            textSize = 20f
            setPadding(0, 0, 0, 12)
        }

        previewView = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = 450
            minimumWidth = 450
            setPadding(0, 0, 0, 24)
        }

        settingsHeaderView = TextView(this).apply {
            text = "Settings"
            textSize = 20f
            setPadding(0, 12, 0, 12)
        }

        statusView = TextView(this).apply {
            textSize = 18f
            setPadding(0, 16, 0, 0)
        }

        recurringSwitch = Switch(this).apply {
            text = "Show daily event labels"
        }
        use24HourSwitch = Switch(this).apply {
            text = "Use 24-hour time"
        }
        hidePastSwitch = Switch(this).apply {
            text = "Hide past event labels"
        }

        container.addView(previewHeaderView)
        container.addView(previewView)
        container.addView(settingsHeaderView)
        container.addView(statusView)
        container.addView(recurringSwitch)
        container.addView(use24HourSwitch)
        container.addView(hidePastSwitch)
        setContentView(ScrollView(this).apply { addView(container) })

        CalendarSyncScheduler.schedulePeriodic(this)
        settings = CalendarSettingsStore.load(this)
        bindSettingsUi()

        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            statusView.text = "Calendar permission granted. Syncing to watch..."
            pushSettingsToWatch("phone")
            CalendarSyncScheduler.triggerImmediate(this)
            refreshPreview()
        } else {
            statusView.text = "Grant calendar permission to sync events to your watch."
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQUEST_CALENDAR_PERMISSION)
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            settingsUpdatedReceiver,
            IntentFilter(SettingsSyncContract.ACTION_SETTINGS_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        settings = CalendarSettingsStore.load(this)
        bindSettingsUi()
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            refreshPreview()
        }
    }

    override fun onPause() {
        unregisterReceiver(settingsUpdatedReceiver)
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
            statusView.text = "Calendar permission granted. Syncing to watch..."
            pushSettingsToWatch("phone")
            CalendarSyncScheduler.triggerImmediate(this)
            refreshPreview()
        }
    }

    private fun bindSettingsUi() {
        recurringSwitch.setOnCheckedChangeListener(null)
        use24HourSwitch.setOnCheckedChangeListener(null)
        hidePastSwitch.setOnCheckedChangeListener(null)

        recurringSwitch.isChecked = settings.showRecurringLabels
        use24HourSwitch.isChecked = settings.use24HourTime
        hidePastSwitch.isChecked = settings.hidePastEventLabels

        recurringSwitch.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(showRecurringLabels = checked)
            applySettingsAndSync()
        }
        use24HourSwitch.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(use24HourTime = checked)
            applySettingsAndSync()
        }
        hidePastSwitch.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(hidePastEventLabels = checked)
            applySettingsAndSync()
        }
    }

    private fun applySettingsAndSync() {
        CalendarSettingsStore.save(this, settings)
        pushSettingsToWatch("phone")
        CalendarSyncScheduler.triggerImmediate(this)
        refreshPreview()
    }

    private fun pushSettingsToWatch(source: String) {
        SettingsSyncTransmitter.push(this, settings, source = source)
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
            settings = settings,
            probe = CalendarRenderProbe(
                dayEvents = dayEvents,
                dayStartMillis = dayBounds.first,
                dayEndMillis = dayBounds.second,
                nowMillis = now
            )
        )
        previewView.setImageBitmap(bitmap)
        statusView.text = "Previewing ${dayEvents.size} events. Syncing to watch..."
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
