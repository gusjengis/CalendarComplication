/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.example.calendarcomplication.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.calendarcomplication.R
import com.example.calendarcomplication.complication.MainComplicationService
import com.example.calendarcomplication.presentation.theme.CalendarComplicationTheme
import com.example.calendarcomplication.settings.WatchSettingsStore

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
    var showRecurringLabels by remember {
        mutableStateOf(WatchSettingsStore.shouldShowRecurringLabels(context))
    }
    var use24HourTime by remember {
        mutableStateOf(WatchSettingsStore.use24HourTime(context))
    }
    var hidePastEventLabels by remember {
        mutableStateOf(WatchSettingsStore.hidePastEventLabels(context))
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 30.dp, bottom = 20.dp)
    ) {
        item {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                text = stringResource(R.string.settings_title),
                color = MaterialTheme.colors.primary
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = showRecurringLabels,
                onCheckedChange = { checked ->
                    showRecurringLabels = checked
                    WatchSettingsStore.setShowRecurringLabels(context, checked)
                    MainComplicationService.forceUpdateNow(context)
                },
                label = { Text(stringResource(R.string.settings_recurring_label_title)) },
                secondaryLabel = { Text(stringResource(R.string.settings_recurring_label_subtitle)) },
                toggleControl = { Switch(checked = showRecurringLabels) }
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = use24HourTime,
                onCheckedChange = { checked ->
                    use24HourTime = checked
                    WatchSettingsStore.setUse24HourTime(context, checked)
                    MainComplicationService.forceUpdateNow(context)
                },
                label = { Text(stringResource(R.string.settings_24_hour_title)) },
                secondaryLabel = { Text(stringResource(R.string.settings_24_hour_subtitle)) },
                toggleControl = { Switch(checked = use24HourTime) }
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = hidePastEventLabels,
                onCheckedChange = { checked ->
                    hidePastEventLabels = checked
                    WatchSettingsStore.setHidePastEventLabels(context, checked)
                    MainComplicationService.forceUpdateNow(context)
                },
                label = { Text(stringResource(R.string.settings_hide_past_labels_title)) },
                secondaryLabel = { Text(stringResource(R.string.settings_hide_past_labels_subtitle)) },
                toggleControl = { Switch(checked = hidePastEventLabels) }
            )
        }
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp()
}
