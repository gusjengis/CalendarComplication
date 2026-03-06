package com.example.calendarcomplication.phone.sync

import com.example.calendarcomplication.core.settings.CalendarSettings
import com.example.calendarcomplication.core.settings.CalendarSettingsStore
import com.example.calendarcomplication.core.sync.SettingsSyncContract
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class SettingsSyncListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { buffer ->
            for (event in buffer) {
                if (event.type != DataEvent.TYPE_CHANGED) {
                    continue
                }
                val item = event.dataItem
                if (item.uri.path != SettingsSyncContract.PATH_SETTINGS_SYNC) {
                    continue
                }

                val map = DataMapItem.fromDataItem(item).dataMap
                if (map.getString(SettingsSyncContract.KEY_SOURCE) == "phone") {
                    continue
                }

                val settings = CalendarSettings(
                    showRecurringLabels = map.getBoolean(SettingsSyncContract.KEY_SHOW_RECURRING_LABELS),
                    use24HourTime = map.getBoolean(SettingsSyncContract.KEY_USE_24_HOUR_TIME),
                    hidePastEventLabels = map.getBoolean(SettingsSyncContract.KEY_HIDE_PAST_EVENT_LABELS)
                )
                CalendarSettingsStore.saveIfNewer(
                    this,
                    settings,
                    map.getLong(SettingsSyncContract.KEY_UPDATED_AT)
                )
            }
        }
    }
}
