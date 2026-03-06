package com.example.calendarcomplication.core.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.ScalingLazyListState
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip

@Composable
fun ComplicationSettingsList(
    settings: CalendarSettings,
    state: ScalingLazyListState,
    title: String,
    recurringTitle: String,
    recurringSubtitle: String,
    twentyFourHourTitle: String,
    twentyFourHourSubtitle: String,
    hidePastTitle: String,
    hidePastSubtitle: String,
    onSettingsChange: (CalendarSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    ScalingLazyColumn(
        modifier = modifier.fillMaxSize(),
        state = state,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 30.dp, bottom = 20.dp)
    ) {
        item {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                text = title,
                textAlign = TextAlign.Center
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = settings.showRecurringLabels,
                onCheckedChange = { checked ->
                    onSettingsChange(settings.copy(showRecurringLabels = checked))
                },
                label = { Text(recurringTitle) },
                secondaryLabel = { Text(recurringSubtitle) },
                toggleControl = { Switch(checked = settings.showRecurringLabels) }
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = settings.use24HourTime,
                onCheckedChange = { checked ->
                    onSettingsChange(settings.copy(use24HourTime = checked))
                },
                label = { Text(twentyFourHourTitle) },
                secondaryLabel = { Text(twentyFourHourSubtitle) },
                toggleControl = { Switch(checked = settings.use24HourTime) }
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = settings.hidePastEventLabels,
                onCheckedChange = { checked ->
                    onSettingsChange(settings.copy(hidePastEventLabels = checked))
                },
                label = { Text(hidePastTitle) },
                secondaryLabel = { Text(hidePastSubtitle) },
                toggleControl = { Switch(checked = settings.hidePastEventLabels) }
            )
        }
    }
}
