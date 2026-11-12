package com.lomo.ui.component.menu

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

internal data class ActionColors(val container: Color, val content: Color)

internal object ActionSheetTokens {
    /** 32.dp expressive sheet corner. */
    val SheetShape = AppShapes.ExtraLargeIncreased
    val ContentHorizontalPadding = AppSpacing.Medium
    val ContentBottomPadding = AppSpacing.Large
    val SectionSpacing = AppSpacing.MediumSmall

    const val MaxTileCount = 3
    val TileShape = AppShapes.ExtraLargeIncreased
    val TileHeight = 96.dp
    val TileSpacing = AppSpacing.Small
    val TileContentPadding = AppSpacing.Small
    val TileIconSize = 26.dp
    val TileIconLabelSpacing = AppSpacing.Small

    val RowShape = AppShapes.Large
    val RowMinHeight = 56.dp
    val RowHorizontalPadding = AppSpacing.Medium
    val RowIconSize = 24.dp
    val RowIconLabelSpacing = AppSpacing.Medium
    val DividerVerticalPadding = AppSpacing.ExtraSmall

    const val InlineCapsuleMaxCount = 2
    const val CapsuleLabelMaxLines = 2
    val CapsuleShape = AppShapes.LargeIncreased
    val CapsuleMinHeight = 56.dp
    val CapsuleContentPadding = AppSpacing.MediumSmall
    val CapsuleIconSize = 20.dp
    val CapsuleIconLabelSpacing = AppSpacing.Small

    val MetaLabelValueSpacing = AppSpacing.ExtraSmall

    fun tileColors(colorScheme: ColorScheme, action: ActionItemUi): ActionColors = when {
        action.isDestructive -> ActionColors(colorScheme.errorContainer, colorScheme.onErrorContainer)
        action.isHighlighted -> ActionColors(colorScheme.primaryContainer, colorScheme.onPrimaryContainer)
        else -> ActionColors(colorScheme.surfaceContainerHigh, colorScheme.onSurface)
    }

    fun capsuleColors(colorScheme: ColorScheme, action: ActionItemUi): ActionColors = when {
        action.isDestructive -> ActionColors(colorScheme.errorContainer, colorScheme.onErrorContainer)
        action.isHighlighted -> ActionColors(colorScheme.primary, colorScheme.onPrimary)
        else -> ActionColors(colorScheme.secondaryContainer, colorScheme.onSecondaryContainer)
    }
}
