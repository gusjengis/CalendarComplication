package com.example.calendarcomplication.phone.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CalendarSyncScheduler.schedulePeriodic(context)
        CalendarSyncScheduler.triggerImmediate(context)
    }
}
