package com.lomo.ui.component.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
 * Material 3 Expressive flavored [TimePicker] surface.
 *
 * Provides a [secondsSlot] hook so callers that need second-precision (e.g., 补记) can mount a
 * [SecondsWheelPicker] directly under the clock face while reminder-precision callers leave it
 * `null`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveTimePickerSurface(
    state: TimePickerState,
    modifier: Modifier = Modifier,
    secondsSlot: (@Composable ColumnScope.() -> Unit)? = null,
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TimePicker(state = state, colors = colors)
        if (secondsSlot != null) {
            secondsSlot()
        }
    }
}
