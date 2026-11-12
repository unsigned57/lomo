package com.lomo.app.feature.settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.ContrastLevel
import com.lomo.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContrastLevelCard(selected: ContrastLevel, onSelect: (ContrastLevel) -> Unit) {
    val options = listOf(
        ContrastLevel.STANDARD to R.string.settings_color_contrast_standard,
        ContrastLevel.MEDIUM to R.string.settings_color_contrast_medium,
        ContrastLevel.HIGH to R.string.settings_color_contrast_high,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = com.lomo.ui.theme.AppShapes.LargeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
        ) {
            Text(
                text = stringResource(R.string.settings_color_contrast_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, labelRes) ->
                    SegmentedButton(
                        selected = selected == value,
                        onClick = { onSelect(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(labelRes))
                    }
                }
            }
        }
    }
}
