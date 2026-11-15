package com.lomo.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.ui.component.common.LomoModalBottomSheet
import com.lomo.ui.component.common.LomoSheetTokens
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList

/**
 * M3 Expressive single-choice picker: a bottom sheet with an emphasized title and segmented
 * radio rows, replacing the plain alert dialog so theme, language, and format pickers share the
 * same elevated segmented surface as the rest of the app.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> SelectionDialog(
    title: String,
    options: ImmutableList<T>,
    currentSelection: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    labelProvider: (T) -> String = { it.toString() },
) {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme
    val unselectedColors =
        ListItemDefaults.segmentedColors(
            containerColor = LomoSheetTokens.tileSurfaceColor(colorScheme),
            contentColor = colorScheme.onSurface,
        )
    val selectedColors =
        ListItemDefaults.segmentedColors(
            containerColor = colorScheme.secondaryContainer,
            contentColor = colorScheme.onSecondaryContainer,
            leadingContentColor = colorScheme.onSecondaryContainer,
        )
    LomoModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Medium)
                .padding(bottom = AppSpacing.ExtraLarge),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.padding(bottom = AppSpacing.MediumSmall),
            )
            LazyColumn(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = SelectionSheetTokens.MaxListHeight),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                itemsIndexed(options) { index, option ->
                    val selected = option == currentSelection
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
                            onSelect(option)
                        },
                        shapes = staticShapes,
                        colors = if (selected) selectedColors else unselectedColors,
                        leadingContent = {
                            RadioButton(selected = selected, onClick = null)
                        },
                    ) {
                        Text(
                            text = labelProvider(option),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

private object SelectionSheetTokens {
    val MaxListHeight = 420.dp
}
