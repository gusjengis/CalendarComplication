package com.example.calendarcomplication.settings

import android.content.Context

object WatchSettingsStore {
    private const val PREFS_NAME = "calendar_complication_settings"
    private const val KEY_SHOW_RECURRING_LABELS = "show_recurring_labels"
    private const val KEY_USE_24_HOUR_TIME = "use_24_hour_time"
    private const val KEY_HIDE_PAST_EVENT_LABELS = "hide_past_event_labels"

    fun shouldShowRecurringLabels(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_RECURRING_LABELS, false)
    }

    fun setShowRecurringLabels(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_RECURRING_LABELS, show).apply()
    }

    fun use24HourTime(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_USE_24_HOUR_TIME, false)
    }

    fun setUse24HourTime(context: Context, use24Hour: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_24_HOUR_TIME, use24Hour).apply()
    }

    fun hidePastEventLabels(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_PAST_EVENT_LABELS, true)
    }

    fun setHidePastEventLabels(context: Context, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_PAST_EVENT_LABELS, hide).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
