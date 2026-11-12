package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.lomo.app.R
import com.lomo.domain.model.ThemeMode
import com.lomo.ui.component.settings.SwitchPreferenceItem
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ThemePreferenceDialog(
    visible: Boolean,
    currentThemeMode: ThemeMode,
    pureBlackDark: Boolean,
    options: ImmutableList<ThemeMode>,
    themeModeLabels: Map<ThemeMode, String>,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onTogglePureBlackDark: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val haptic = LocalAppHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme
    val unselectedColors =
        ListItemDefaults.segmentedColors(
            containerColor = colorScheme.surfaceContainerHigh,
            contentColor = colorScheme.onSurface,
        )
    val selectedColors =
        ListItemDefaults.segmentedColors(
            containerColor = colorScheme.secondaryContainer,
            contentColor = colorScheme.onSecondaryContainer,
            leadingContentColor = colorScheme.onSecondaryContainer,
        )

    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.ExtraLargeIncreased,
        containerColor = colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Medium)
                .padding(bottom = AppSpacing.ExtraLarge),
        ) {
            Text(
                text = stringResource(R.string.settings_select_theme),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.padding(bottom = AppSpacing.MediumSmall),
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                options.forEachIndexed { index, option ->
                    val selected = option == currentThemeMode
                    val baseShape = ListItemDefaults.segmentedShapes(index, options.size).shape
                    val staticShapes =
                        ListItemDefaults.shapes(
                            shape = baseShape,
                            selectedShape = baseShape,
                            pressedShape = baseShape,
                            focusedShape = baseShape,
                            hoveredShape = baseShape,
                            draggedShape = baseShape,
                        )
                    SegmentedListItem(
                        selected = selected,
                        onClick = {
                            haptic.medium()
                            onSelectThemeMode(option)
                        },
                        shapes = staticShapes,
                        colors = if (selected) selectedColors else unselectedColors,
                        leadingContent = {
                            RadioButton(selected = selected, onClick = null)
                        },
                    ) {
                        Text(
                            text = themeModeLabels[option] ?: option.value,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Medium))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colorScheme.surfaceContainerHigh,
                shape = AppShapes.LargeIncreased,
            ) {
                SwitchPreferenceItem(
                    title = stringResource(R.string.settings_pure_black_dark),
                    subtitle = stringResource(R.string.settings_pure_black_dark_subtitle),
                    icon = Icons.Outlined.DarkMode,
                    checked = pureBlackDark,
                    onCheckedChange = onTogglePureBlackDark,
                )
            }
        }
    }
}
