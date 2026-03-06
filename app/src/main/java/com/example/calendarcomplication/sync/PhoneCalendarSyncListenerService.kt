package com.example.calendarcomplication.sync

import android.util.Log
import com.example.calendarcomplication.complication.MainComplicationService
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
                if (item.uri.path != PATH_CALENDAR_SYNC) {
                    continue
                }

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
