package com.lomo.ui.component.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

internal object MarkdownComponentTokens {
    val HorizontalRuleVerticalPadding = AppSpacing.Small
    val BlockVerticalPadding = AppSpacing.ExtraSmall
    val CodeBlockShape = AppShapes.ExtraSmall
    val CodeBlockContentPadding = AppSpacing.Small
    val TableShape = AppShapes.ExtraSmall
    val TableBorderWidth = 1.dp
    val TableCellPadding = PaddingValues(horizontal = AppSpacing.Small, vertical = 6.dp)
    val BlockQuoteStartPadding = AppSpacing.ExtraSmall
    val BlockQuoteContentGap = AppSpacing.Small
    val ListIndent = AppSpacing.Small
    val ListItemVerticalPadding = 2.dp
    val BulletSize = 6.dp
    val LeadingSlotWidth = 28.dp
    val LeadingVisualWidth = 24.dp
    val GallerySpacing = AppSpacing.Small

    private const val CodeBlockContainerAlpha = 0.5f
    private const val TableHeaderAlpha = 0.35f

    fun codeBlockContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.surfaceVariant.copy(alpha = CodeBlockContainerAlpha)

    fun tableHeaderColor(colorScheme: ColorScheme): Color =
        colorScheme.surfaceVariant.copy(alpha = TableHeaderAlpha)
}

internal object MarkdownImageTokens {
    val Shape = AppShapes.Small
    val VerticalPadding = AppSpacing.ExtraSmall
    val PagerTopPadding = 6.dp
    val IndicatorHorizontalPadding = 3.dp
    val IndicatorActiveSize = AppSpacing.Small
    val IndicatorInactiveSize = 6.dp
    val IndicatorShape = AppShapes.Full
    val LoadingMinHeight = 100.dp
    val PlaceholderMinHeight = 60.dp
    val ErrorHeight = 60.dp
    val LoadingIndicatorSize = 24.dp
    val PlaceholderContentPadding = AppSpacing.Medium

    private const val IndicatorActiveAlpha = 0.85f
    private const val IndicatorInactiveAlpha = 0.65f
    private const val LoadingContainerAlpha = 0.3f
    private const val LoadingIndicatorAlpha = 0.6f
    private const val ErrorContainerAlpha = 0.3f
    private const val EmptyContainerAlpha = 0.2f

    fun activeIndicatorColor(colorScheme: ColorScheme): Color =
        colorScheme.primary.copy(alpha = IndicatorActiveAlpha)

    fun inactiveIndicatorColor(colorScheme: ColorScheme): Color =
        colorScheme.outlineVariant.copy(alpha = IndicatorInactiveAlpha)

    fun loadingContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.surfaceVariant.copy(alpha = LoadingContainerAlpha)

    fun loadingIndicatorColor(colorScheme: ColorScheme): Color =
        colorScheme.primary.copy(alpha = LoadingIndicatorAlpha)

    fun errorContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.errorContainer.copy(alpha = ErrorContainerAlpha)

    fun emptyContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.surfaceVariant.copy(alpha = EmptyContainerAlpha)
}
