package com.example.calendarcomplication.phone.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CalendarSyncScheduler {
    private const val PERIODIC_WORK_NAME = "calendar_companion_periodic_sync"
    private const val IMMEDIATE_WORK_NAME = "calendar_companion_immediate_sync"

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun triggerImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
