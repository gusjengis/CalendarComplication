package com.example.calendarcomplication.phone.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CalendarSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!hasCalendarPermission(applicationContext)) {
            return Result.success()
        }

        return if (CalendarSyncTransmitter.sync(applicationContext)) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    private fun hasCalendarPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }
}
