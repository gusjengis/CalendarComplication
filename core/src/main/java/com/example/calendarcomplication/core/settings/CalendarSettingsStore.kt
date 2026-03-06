package com.example.calendarcomplication.core.settings

import android.content.Context

object CalendarSettingsStore {
    private const val PREFS_NAME = "calendar_complication_settings"
    private const val KEY_SHOW_RECURRING_LABELS = "show_recurring_labels"
    private const val KEY_USE_24_HOUR_TIME = "use_24_hour_time"
    private const val KEY_HIDE_PAST_EVENT_LABELS = "hide_past_event_labels"
    private const val KEY_LAST_UPDATED_MILLIS = "settings_last_updated_millis"

    fun load(context: Context): CalendarSettings {
        val prefs = prefs(context)
        return CalendarSettings(
            showRecurringLabels = prefs.getBoolean(KEY_SHOW_RECURRING_LABELS, false),
            use24HourTime = prefs.getBoolean(KEY_USE_24_HOUR_TIME, false),
            hidePastEventLabels = prefs.getBoolean(KEY_HIDE_PAST_EVENT_LABELS, true)
        )
    }

    fun save(context: Context, settings: CalendarSettings, updatedAtMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putBoolean(KEY_SHOW_RECURRING_LABELS, settings.showRecurringLabels)
            .putBoolean(KEY_USE_24_HOUR_TIME, settings.use24HourTime)
            .putBoolean(KEY_HIDE_PAST_EVENT_LABELS, settings.hidePastEventLabels)
            .putLong(KEY_LAST_UPDATED_MILLIS, updatedAtMillis)
            .apply()
    }

    fun saveIfNewer(context: Context, settings: CalendarSettings, updatedAtMillis: Long): Boolean {
        if (updatedAtMillis <= lastUpdatedMillis(context)) {
            return false
        }
        save(context, settings, updatedAtMillis)
        return true
    }

    fun lastUpdatedMillis(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_UPDATED_MILLIS, 0L)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
