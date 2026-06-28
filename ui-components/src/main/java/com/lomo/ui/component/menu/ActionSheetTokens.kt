package com.lomo.ui.component.menu

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

internal object ActionSheetTokens {
    val ContentHorizontalPadding = AppSpacing.Medium
    val ContentBottomPadding = AppSpacing.ExtraLarge
    val RowVerticalPadding = AppSpacing.Medium
    val ItemSpacing = AppSpacing.MediumSmall
    val EqualWidthActionWidth = 92.dp
    val DividerVerticalPadding = AppSpacing.Small
    val SwipeEdgeSpacing = AppSpacing.MediumSmall
    val SwipeTrackWidth = 112.dp
    val SwipeTrackHeight = 6.dp
    val SwipeThumbWidth = 28.dp
    val SwipeShape = AppShapes.Full
    val SwipeEdgeButtonSize = 40.dp
    val SwipeEdgeIconSize = 18.dp
    val ActionChipShape = AppShapes.Medium
    val ActionChipHeight = 64.dp
    val ActionChipMinWidth = 72.dp
    val ActionChipPadding = AppSpacing.Small
    val ActionChipIconSize = 22.dp
    val ActionChipIconLabelSpacing = AppSpacing.ExtraSmall
    val InfoCardShape = AppShapes.Medium
    val InfoCardTopPadding = AppSpacing.Small
    val InfoCardPadding = AppSpacing.Medium
    val InfoItemTextSpacing = 2.dp

    const val DragScaleFactor = 1.05f
    const val DragAlpha = 0.92f

    private const val DividerAlpha = 0.5f
    private const val SwipeTrackAlpha = 0.35f
    private const val SwipeThumbAlpha = 0.9f
    private const val ActionChipContainerAlpha = 0.7f
    private const val InfoCardContainerAlpha = 0.5f

    fun dividerColor(colorScheme: ColorScheme): Color =
        colorScheme.outlineVariant.copy(alpha = DividerAlpha)

    fun swipeTrackColor(colorScheme: ColorScheme): Color =
        colorScheme.outlineVariant.copy(alpha = SwipeTrackAlpha)

    fun swipeThumbColor(colorScheme: ColorScheme): Color =
        colorScheme.secondaryContainer.copy(alpha = SwipeThumbAlpha)

    fun actionChipContainerColor(
        colorScheme: ColorScheme,
        isDestructive: Boolean,
        isHighlighted: Boolean,
    ): Color =
        when {
            isDestructive -> colorScheme.errorContainer.copy(alpha = ActionChipContainerAlpha)
            isHighlighted -> colorScheme.primaryContainer
            else -> colorScheme.secondaryContainer.copy(alpha = ActionChipContainerAlpha)
        }

    fun infoCardContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.surfaceVariant.copy(alpha = InfoCardContainerAlpha)
}
