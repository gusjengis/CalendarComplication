package com.example.calendarcomplication.complication

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

class ComplicationWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!ComplicationWatchdogScheduler.isEnabled(context)) {
            ComplicationWatchdogScheduler.stop(context)
            return
        }

        MainComplicationService.forceUpdateNow(context)
        ComplicationWatchdogScheduler.scheduleNext(context)
    }
}

object ComplicationWatchdogScheduler {
    private const val ACTION_WATCHDOG_TICK = "com.example.calendarcomplication.action.WATCHDOG_TICK"
    private const val WATCHDOG_INTERVAL_MS = 60_000L
    private const val PREFS_NAME = "calendar_complication_watchdog"
    private const val KEY_ENABLED = "enabled"
    private const val REQUEST_CODE_WATCHDOG = 9001

    fun start(context: Context) {
        setEnabled(context, true)
        scheduleNext(context)
    }

    fun stop(context: Context) {
        setEnabled(context, false)
        alarmManager(context).cancel(pendingIntent(context))
    }

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun scheduleNext(context: Context) {
        val triggerAt = nextMinuteBoundaryElapsedRealtime()
        val alarmManager = alarmManager(context)
        val pendingIntent = pendingIntent(context)

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms() -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }

            else -> {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        }
    }

    private fun nextMinuteBoundaryElapsedRealtime(): Long {
        val now = SystemClock.elapsedRealtime()
        return ((now / WATCHDOG_INTERVAL_MS) + 1L) * WATCHDOG_INTERVAL_MS
    }

    private fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun alarmManager(context: Context): AlarmManager {
        return context.getSystemService(AlarmManager::class.java)
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ComplicationWatchdogReceiver::class.java).apply {
            action = ACTION_WATCHDOG_TICK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_WATCHDOG,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
