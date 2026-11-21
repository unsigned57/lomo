package com.lomo.ui.component.dialog

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.ui.component.common.LomoModalBottomSheet
import com.lomo.ui.component.common.LomoSheetTokens
import com.lomo.ui.component.common.rememberModalSheetPresentation
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
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
    val presentation = rememberModalSheetPresentation()
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    LomoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = presentation.sheetState,
        modifier = modifier,
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
                    SelectionSheetOptionRow(
                        index = index,
                        count = options.size,
                        selected = option == currentSelection,
                        label = labelProvider(option),
                        onClick = {
                            haptic.medium()
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SelectionSheetOptionRow(index: Int, count: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val componentMotion = LocalLomoMotion.current.component
    val containerColor by animateColorAsState(
        targetValue = if (selected) colorScheme.secondaryContainer else LomoSheetTokens.tileSurfaceColor(colorScheme),
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "SelectionOptionContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurface,
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "SelectionOptionContent",
    )
    val leadingColor by animateColorAsState(
        targetValue = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant,
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "SelectionOptionLeading",
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

private object SelectionSheetTokens {
    val MaxListHeight = 420.dp
}
