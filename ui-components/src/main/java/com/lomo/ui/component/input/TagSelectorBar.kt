package com.lomo.ui.component.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppSpacing

data class TagSelectorBarState(
    val availableTags: List<String> = emptyList(),
)

data class TagSelectorBarCallbacks(
    val onTagSelected: (String) -> Unit,
)

@Composable
fun TagSelectorBar(
    state: TagSelectorBarState,
    callbacks: TagSelectorBarCallbacks,
) {
    Column {
        Text(
            androidx.compose.ui.res.stringResource(
                com.lomo.ui.R.string.input_select_tag,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = AppSpacing.Small),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(32.dp),
        ) {
            items(state.availableTags) { tag ->
                FilterChip(
                    selected = false,
                    onClick = { callbacks.onTagSelected(tag) },
                    label = { Text("#$tag") },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    border = null,
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.MediumSmall))
    }
}
