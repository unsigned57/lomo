package com.lomo.ui.component.picker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive flavored [TimePicker] surface (hours + minutes only).
 *
 * Second precision is intentionally not part of this surface: callers that need it (e.g. 补录) present
 * seconds on their own dedicated step via [SecondsWheelPicker], so this surface stays a single,
 * screen-fitting picker that never needs to share a page with another control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveTimePickerSurface(
    state: TimePickerState,
    modifier: Modifier = Modifier,
) {
    val colors = TimePickerDefaults.colors(
        selectorColor = MaterialTheme.colorScheme.primary,
        timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        clockDialSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TimePicker(state = state, colors = colors)
    }
}
