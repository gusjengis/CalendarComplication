package com.example.calendarcomplication.sync

import android.content.Intent
import android.util.Log
import com.example.calendarcomplication.complication.MainComplicationService
import com.example.calendarcomplication.core.settings.CalendarSettings
import com.example.calendarcomplication.core.settings.CalendarSettingsStore
import com.example.calendarcomplication.core.sync.SettingsSyncContract
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class PhoneCalendarSyncListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { buffer ->
            for (event in buffer) {
                if (event.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) {
                    continue
                }

                val item = event.dataItem
                when (item.uri.path) {
                    PATH_CALENDAR_SYNC -> {
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val updatedAt = map.getLong(KEY_UPDATED_AT)
                        val accountHint = map.getString(KEY_ACCOUNT_HINT) ?: "phone"
                        val eventsJson = map.getString(KEY_EVENTS_JSON) ?: "[]"

                        PhoneCalendarSyncStore.save(
                            context = this,
                            updatedAtMillis = updatedAt,
                            accountHint = accountHint,
                            eventsJson = eventsJson
                        )
                        Log.d(TAG, "Received phone calendar sync payload")
                        MainComplicationService.forceUpdateNow(this)
                    }

                    SettingsSyncContract.PATH_SETTINGS_SYNC -> {
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val source = map.getString(SettingsSyncContract.KEY_SOURCE)
                        if (source == "watch") {
                            continue
                        }
                        val updatedAt = map.getLong(SettingsSyncContract.KEY_UPDATED_AT)
                        val settings = CalendarSettings(
                            showRecurringLabels = map.getBoolean(SettingsSyncContract.KEY_SHOW_RECURRING_LABELS),
                            use24HourTime = map.getBoolean(SettingsSyncContract.KEY_USE_24_HOUR_TIME),
                            hidePastEventLabels = map.getBoolean(SettingsSyncContract.KEY_HIDE_PAST_EVENT_LABELS)
                        )
                        CalendarSettingsStore.save(this, settings, updatedAt)
                        Log.d(TAG, "Applied settings from phone")
                        MainComplicationService.forceUpdateNow(this)
                        sendBroadcast(
                            Intent(SettingsSyncContract.ACTION_SETTINGS_UPDATED)
                                .setPackage(packageName)
                        )
                    }

                    else -> continue
                }
            }
        }
    }

    companion object {
        private const val TAG = "PhoneCalendarSync"
        const val PATH_CALENDAR_SYNC = "/calendar_sync/events"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_ACCOUNT_HINT = "account_hint"
        const val KEY_EVENTS_JSON = "events_json"
    }
}
