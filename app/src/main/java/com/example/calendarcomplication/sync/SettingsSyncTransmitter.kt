package com.example.calendarcomplication.sync

import android.content.Context
import com.example.calendarcomplication.core.settings.CalendarSettings
import com.example.calendarcomplication.core.sync.SettingsSyncContract
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object SettingsSyncTransmitter {
    fun push(
        context: Context,
        settings: CalendarSettings,
        updatedAtMillis: Long = System.currentTimeMillis(),
        source: String
    ): Boolean {
        return runCatching {
            val request = PutDataMapRequest.create(SettingsSyncContract.PATH_SETTINGS_SYNC).apply {
                dataMap.putLong(SettingsSyncContract.KEY_UPDATED_AT, updatedAtMillis)
                dataMap.putBoolean(SettingsSyncContract.KEY_SHOW_RECURRING_LABELS, settings.showRecurringLabels)
                dataMap.putBoolean(SettingsSyncContract.KEY_USE_24_HOUR_TIME, settings.use24HourTime)
                dataMap.putBoolean(SettingsSyncContract.KEY_HIDE_PAST_EVENT_LABELS, settings.hidePastEventLabels)
                dataMap.putString(SettingsSyncContract.KEY_SOURCE, source)
            }.asPutDataRequest().setUrgent()

            Tasks.await(Wearable.getDataClient(context).putDataItem(request))
            true
        }.getOrDefault(false)
    }
}
