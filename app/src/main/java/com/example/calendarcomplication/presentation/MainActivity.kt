/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.example.calendarcomplication.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.rememberScalingLazyListState
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.calendarcomplication.R
import com.example.calendarcomplication.complication.MainComplicationService
import com.example.calendarcomplication.core.settings.ComplicationSettingsList
import com.example.calendarcomplication.presentation.theme.CalendarComplicationTheme
import com.example.calendarcomplication.core.settings.CalendarSettingsStore
import com.example.calendarcomplication.core.sync.SettingsSyncContract
import com.example.calendarcomplication.sync.SettingsSyncTransmitter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            WearApp()
        }
    }
}

@Composable
fun WearApp() {
    CalendarComplicationTheme {
        Scaffold(timeText = { TimeText() }) {
            SettingsScreen()
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var hasCalendarPermission by remember {
        mutableStateOf(hasReadCalendarPermission(context))
    }
    var hasRequestedCalendarPermission by rememberSaveable { mutableStateOf(false) }
    val requestCalendarPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCalendarPermission = granted
        if (granted) {
            MainComplicationService.forceUpdateNow(context)
        }
    }

    LaunchedEffect(hasCalendarPermission, hasRequestedCalendarPermission) {
        if (hasCalendarPermission) {
            MainComplicationService.forceUpdateNow(context)
            return@LaunchedEffect
        }
        if (!hasRequestedCalendarPermission) {
            hasRequestedCalendarPermission = true
            requestCalendarPermission.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(CalendarSettingsStore.load(context)) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != SettingsSyncContract.ACTION_SETTINGS_UPDATED) {
                    return
                }
                settings = CalendarSettingsStore.load(context)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(SettingsSyncContract.ACTION_SETTINGS_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        SettingsSyncTransmitter.push(context, settings, source = "watch")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .focusTarget()
            .onPreRotaryScrollEvent {
                coroutineScope.launch {
                    listState.scrollBy(it.verticalScrollPixels)
                }
                true
            }
            .onRotaryScrollEvent {
                coroutineScope.launch {
                    listState.scrollBy(it.verticalScrollPixels)
                }
                true
            }
    ) {
        ComplicationSettingsList(
            settings = settings,
            state = listState,
            title = stringResource(R.string.settings_title),
            recurringTitle = stringResource(R.string.settings_recurring_label_title),
            recurringSubtitle = stringResource(R.string.settings_recurring_label_subtitle),
            twentyFourHourTitle = stringResource(R.string.settings_24_hour_title),
            twentyFourHourSubtitle = stringResource(R.string.settings_24_hour_subtitle),
            hidePastTitle = stringResource(R.string.settings_hide_past_labels_title),
            hidePastSubtitle = stringResource(R.string.settings_hide_past_labels_subtitle),
            onSettingsChange = { newSettings ->
                settings = newSettings
                CalendarSettingsStore.save(context, settings)
                SettingsSyncTransmitter.push(context, settings, source = "watch")
                MainComplicationService.forceUpdateNow(context)
            }
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp()
}

private fun hasReadCalendarPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED
}
