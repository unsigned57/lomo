package com.lomo.ui.component.picker

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive flavored [DatePicker] surface.
 *
 * - Always renders the calendar grid (no input/calendar mode toggle), matching the way the three
 *   call sites use the picker today.
 * - Re-tints today / selected day chips to the Expressive primary container palette so the picker
 *   reads as part of the surrounding [ExpressivePickerDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveDatePickerSurface(
    state: DatePickerState,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val colors = DatePickerDefaults.colors(
        todayContentColor = MaterialTheme.colorScheme.primary,
        todayDateBorderColor = MaterialTheme.colorScheme.primary,
        selectedDayContainerColor = MaterialTheme.colorScheme.primary,
        selectedDayContentColor = MaterialTheme.colorScheme.onPrimary,
        currentYearContentColor = MaterialTheme.colorScheme.primary,
        selectedYearContainerColor = MaterialTheme.colorScheme.primary,
        selectedYearContentColor = MaterialTheme.colorScheme.onPrimary,
    )
    DatePicker(
        state = state,
        showModeToggle = false,
        title = title?.let {
            {
                androidx.compose.material3.Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
                )
            }
        },
        headline = null,
        colors = colors,
        modifier = modifier.fillMaxWidth(),
    )
}
