package com.lomo.ui.component.common

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

internal object SkeletonTokens {
    val LineShape = AppShapes.ExtraSmall
    val ActionShape = AppShapes.Medium
    val ItemContentPadding = AppSpacing.Medium
    val ItemVerticalSpacing = AppSpacing.MediumSmall
    val HeaderSpacing = AppSpacing.Small
    val FooterTopPadding = AppSpacing.Small
    val FooterSpacing = AppSpacing.Medium

    private const val ShimmerHighlightAlpha = 0.6f

    fun shimmerHighlightColor(colorScheme: ColorScheme): Color =
        colorScheme.surfaceContainerHighest.copy(alpha = ShimmerHighlightAlpha)
}

internal object EmptyStateTokens {
    val ContentPadding = AppSpacing.ExtraLarge
    val IconSize = 64.dp
    val IconBottomPadding = AppSpacing.Medium
    val DescriptionTopPadding = AppSpacing.Small
    val ActionSpacing = AppSpacing.Medium

    private const val IconAlpha = 0.5f
    private const val DescriptionAlpha = 0.7f

    fun iconColor(colorScheme: ColorScheme): Color =
        colorScheme.onSurfaceVariant.copy(alpha = IconAlpha)

    fun descriptionColor(colorScheme: ColorScheme): Color =
        colorScheme.onSurfaceVariant.copy(alpha = DescriptionAlpha)
}

internal object LoadingOverlayTokens {
    const val ZIndex = 100f
    val MessageSpacing = AppSpacing.Medium

    private const val BackgroundAlpha = 0.9f

    fun backgroundColor(colorScheme: ColorScheme): Color =
        colorScheme.background.copy(alpha = BackgroundAlpha)
}

internal object DraggableScrollbarTokens {
    const val FadeOutDelayMillis = 1200L
    val TrackPadding = 2.dp
    val EndPadding = AppSpacing.Small
    val IdleWidth = AppSpacing.ExtraSmall
    val DragWidth = AppSpacing.MediumSmall
    val TouchTargetWidth = AppSpacing.ExtraLarge
    val ThumbHeight = 36.dp

    const val IdleAlpha = 0.12f
    const val ActiveAlpha = 0.25f
    const val DragAlpha = 0.55f
}
