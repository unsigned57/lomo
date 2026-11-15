package com.lomo.ui.component.common

import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

/**
 * Shared design tokens for modal bottom sheets and interactive sheet blocks.
 *
 * Uses [ColorScheme.surfaceContainerLow] with zero tonal elevation (and [Color.Black] in OLED
 * dark mode) while neutral tiles stand distinctly at [ColorScheme.surfaceContainerHigh].
 */
object LomoSheetTokens {
    val SheetShape = AppShapes.ExtraLargeIncreased
    val TileShape = AppShapes.LargeIncreased
    val TilePressedShape = AppShapes.Medium
    val CapsuleShape = AppShapes.LargeIncreased
    val CapsulePressedShape = AppShapes.Medium
    val CardButtonShape = AppShapes.Medium
    val CardButtonPressedShape = AppShapes.Small
    val TonalElevation = 0.dp

    val DragHandleWidth = AppSpacing.ExtraLarge
    val DragHandleHeight = AppSpacing.ExtraSmall
    val DragHandleTopPadding = AppSpacing.MediumSmall
    val DragHandleBottomPadding = AppSpacing.MediumSmall
    private const val DRAG_HANDLE_ALPHA = 0.36f

    fun containerColor(colorScheme: ColorScheme): Color = colorScheme.surfaceContainerLow

    fun tileSurfaceColor(colorScheme: ColorScheme): Color = colorScheme.surfaceContainerHigh

    fun dragHandleColor(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant.copy(alpha = DRAG_HANDLE_ALPHA)
}

/**
 * Canonical expressive [ButtonShapes] presets unifying block/tile, card-field, and capsule corners
 * across sheets and feature screens.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object LomoButtonDefaults {
    fun tileShapes(): ButtonShapes = ButtonShapes(
        shape = LomoSheetTokens.TileShape,
        pressedShape = LomoSheetTokens.TilePressedShape,
    )

    fun capsuleShapes(): ButtonShapes = ButtonShapes(
        shape = LomoSheetTokens.CapsuleShape,
        pressedShape = LomoSheetTokens.CapsulePressedShape,
    )

    fun cardFieldShapes(): ButtonShapes = ButtonShapes(
        shape = LomoSheetTokens.CardButtonShape,
        pressedShape = LomoSheetTokens.CardButtonPressedShape,
    )
}
