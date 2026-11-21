package com.lomo.app.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.lomo.app.R
import com.lomo.domain.model.ThemeMode
import com.lomo.ui.component.common.LomoModalBottomSheet
import com.lomo.ui.component.common.LomoSheetTokens
import com.lomo.ui.component.common.rememberModalSheetPresentation
import com.lomo.ui.component.settings.SwitchPreferenceItem
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
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

    val presentation = rememberModalSheetPresentation()
    val haptic = LocalAppHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme

    LomoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = presentation.sheetState,
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged(presentation::onContentMeasured)
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
                    ThemePreferenceOptionRow(
                        index = index,
                        count = options.size,
                        selected = option == currentThemeMode,
                        label = themeModeLabels[option] ?: option.value,
                        onClick = {
                            haptic.medium()
                            onSelectThemeMode(option)
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Medium))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = LomoSheetTokens.tileSurfaceColor(colorScheme),
                shape = AppShapes.LargeIncreased,
            ) {
                SwitchPreferenceItem(
                    title = stringResource(R.string.settings_pure_black_dark),
                    subtitle = stringResource(R.string.settings_pure_black_dark_subtitle),
                    icon = LomoIcons.DarkMode,
                    checked = pureBlackDark,
                    onCheckedChange = onTogglePureBlackDark,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemePreferenceOptionRow(index: Int, count: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val componentMotion = LocalLomoMotion.current.component
    val containerColor by animateColorAsState(
        targetValue = if (selected) colorScheme.secondaryContainer else LomoSheetTokens.tileSurfaceColor(colorScheme),
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "ThemeOptionContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurface,
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "ThemeOptionContent",
    )
    val leadingColor by animateColorAsState(
        targetValue = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant,
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "ThemeOptionLeading",
    )
    val baseShape = ListItemDefaults.segmentedShapes(index, count).shape
    val shapes =
        ListItemDefaults.shapes(
            shape = baseShape,
            selectedShape = baseShape,
            pressedShape = LomoSheetTokens.TilePressedShape,
            focusedShape = baseShape,
            hoveredShape = baseShape,
            draggedShape = baseShape,
        )
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = shapes,
        colors =
        ListItemDefaults.segmentedColors(
            containerColor = containerColor,
            contentColor = contentColor,
            leadingContentColor = leadingColor,
        ),
        leadingContent = {
            RadioButton(selected = selected, onClick = null)
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
