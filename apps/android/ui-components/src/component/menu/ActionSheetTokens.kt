package com.lomo.ui.component.menu

import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lomo.ui.component.common.LomoButtonDefaults
import com.lomo.ui.component.common.LomoSheetTokens
import com.lomo.ui.theme.AppSpacing

internal data class ActionColors(val container: Color, val content: Color)

internal object ActionSheetTokens {
    val SheetShape = LomoSheetTokens.SheetShape
    val ContentHorizontalPadding = AppSpacing.Medium
    val ContentBottomPadding = AppSpacing.Large
    val SectionSpacing = AppSpacing.MediumSmall

    const val MaxTileCount = 4
    val TileShape = LomoSheetTokens.TileShape
    val TilePressedShape = LomoSheetTokens.TilePressedShape
    val TileHeight = 68.dp
    val TileSpacing = AppSpacing.Small
    val TileContentPadding = AppSpacing.ExtraSmall
    val TileIconSize = 22.dp
    val TileIconLabelSpacing = 6.dp

    const val InlineCapsuleMaxCount = 2
    const val CapsuleLabelMaxLines = 2
    val CapsuleShape = LomoSheetTokens.CapsuleShape
    val CapsuleMinHeight = 48.dp
    val CapsuleContentPadding = AppSpacing.MediumSmall
    val CapsuleIconSize = 20.dp
    val CapsuleIconLabelSpacing = AppSpacing.Small

    val InfoCardShape = LomoSheetTokens.CapsuleShape
    val InfoCardHorizontalPadding = AppSpacing.Medium
    val InfoCardVerticalPadding = AppSpacing.MediumSmall

    val RadialBlurRadius = 20.dp
    const val RadialScrimAlpha = 0.62f
    const val RadialLabelAlpha = 0.94f
    val RadialButtonSize = 56.dp
    val RadialIconSize = 24.dp
    val RadialLabelTopSpacing = 6.dp
    val RadialLabelHorizontalPadding = 8.dp
    val RadialLabelVerticalPadding = 3.dp
    val RadialItemSpanWidth = 96.dp
    val RadialItemTopExtent = 28.dp
    val RadialItemBottomExtent = 64.dp
    val RadialAnchorWidth = 104.dp
    val RadialAnchorHeight = 52.dp
    val RadialAnchorBottomMargin = 28.dp
    val RadialTwoItemRadius = 128.dp
    val RadialThreeItemRadius = 140.dp
    val RadialEdgeMargin = AppSpacing.Medium

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    fun tileShapes(): ButtonShapes = LomoButtonDefaults.tileShapes()

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    fun capsuleShapes(): ButtonShapes = LomoButtonDefaults.capsuleShapes()

    fun tileColors(colorScheme: ColorScheme, action: ActionItemUi): ActionColors = when {
        action.isDestructive -> ActionColors(colorScheme.errorContainer, colorScheme.onErrorContainer)
        action.isHighlighted -> ActionColors(colorScheme.primaryContainer, colorScheme.onPrimaryContainer)
        else -> ActionColors(LomoSheetTokens.tileSurfaceColor(colorScheme), colorScheme.onSurface)
    }

    fun capsuleColors(colorScheme: ColorScheme, action: ActionItemUi): ActionColors = when {
        action.isDestructive -> ActionColors(colorScheme.errorContainer, colorScheme.onErrorContainer)
        action.isHighlighted -> ActionColors(colorScheme.primary, colorScheme.onPrimary)
        else -> ActionColors(LomoSheetTokens.tileSurfaceColor(colorScheme), colorScheme.onSurface)
    }

    fun infoCardColor(colorScheme: ColorScheme): Color = colorScheme.surfaceContainer

    fun radialLabelContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.surfaceContainerHigh.copy(alpha = RadialLabelAlpha)

    fun radialScrimColor(colorScheme: ColorScheme, alphaFraction: Float = 1f): Color =
        colorScheme.scrim.copy(alpha = (RadialScrimAlpha * alphaFraction).coerceIn(0f, 1f))
}

internal data class RadialOverlayBlurSpec(
    val alpha: Float,
    val blurRadius: Dp,
    val tintColor: Color,
    val fallbackTintColor: Color,
)

internal fun resolveRadialOverlayBlurSpec(effectsAlpha: Float, colorScheme: ColorScheme): RadialOverlayBlurSpec {
    val alpha = effectsAlpha.coerceIn(0f, 1f)
    return RadialOverlayBlurSpec(
        alpha = alpha,
        blurRadius = ActionSheetTokens.RadialBlurRadius,
        tintColor = ActionSheetTokens.radialScrimColor(colorScheme, alphaFraction = 1f),
        fallbackTintColor = ActionSheetTokens.radialScrimColor(colorScheme, alphaFraction = alpha),
    )
}
