package com.example.calendarcomplication.phone

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import com.example.calendarcomplication.phone.sync.CalendarSyncScheduler

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            textSize = 18f
            setPadding(48, 72, 48, 72)
        }
        setContentView(status)

        CalendarSyncScheduler.schedulePeriodic(this)

        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            status.text = "Calendar permission granted. Syncing to watch..."
            CalendarSyncScheduler.triggerImmediate(this)
        } else {
            status.text = "Grant calendar permission to sync events to your watch."
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQUEST_CALENDAR_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALENDAR_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            CalendarSyncScheduler.triggerImmediate(this)
        }
    }

    companion object {
        private const val REQUEST_CALENDAR_PERMISSION = 1001
    }
}
