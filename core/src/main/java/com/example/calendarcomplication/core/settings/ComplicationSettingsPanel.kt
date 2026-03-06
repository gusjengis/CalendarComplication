package com.example.calendarcomplication.core.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip

@Composable
fun ComplicationSettingsPanel(
    settings: CalendarSettings,
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            text = title,
            textAlign = TextAlign.Center
        )
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
