package com.lomo.ui.component.menu

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.LocalLomoHazeState
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.component.common.LomoFilledTonalIconButton
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.cd_close_action_group
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.LocalLomoMotion
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val RADIAL_PAIR_COUNT = 2
private const val RADIAL_TRIAD_COUNT = 3
private const val RADIAL_TWO_ITEM_SPREAD_DEG = 32f
private const val RADIAL_THREE_ITEM_SPREAD_DEG = 46f
private const val RADIAL_MULTI_ITEM_MAX_SPREAD_DEG = 60f
private const val MIN_COSINE_DENOMINATOR = 0.01f
private const val MIN_SINE_THRESHOLD = 0.001f
private const val DEGREES_TO_RADIANS = PI / 180.0
private const val RADIAL_ANCHOR_MIN_SCALE = 0.75f
private const val RADIAL_ANCHOR_SCALE_DELTA = 0.25f
private const val RADIAL_ITEM_MIN_SCALE = 0.4f
private const val RADIAL_ITEM_SCALE_DELTA = 0.6f

internal data class RadialArcViewport(
    val widthPx: Float,
    val heightPx: Float,
    val topInsetPx: Float,
    val bottomInsetPx: Float,
)

internal data class RadialArcSpec(
    val anchorBottomMarginPx: Float,
    val anchorHeightPx: Float,
    val itemHalfSpanPx: Float,
    val itemTopExtentPx: Float,
    val itemBottomExtentPx: Float,
    val preferredRadiusPx: Float,
    val edgeMarginPx: Float = itemHalfSpanPx,
)

internal data class RadialArcItemPosition(val offsetXPx: Float, val offsetYPx: Float)

internal data class RadialArcLayout(
    val centerXPx: Float,
    val anchorCenterYPx: Float,
    val items: List<RadialArcItemPosition>,
)

/**
 * Computes symmetric polar positions above the bottom anchor pill while keeping every item
 * clamped inside the viewport's safe horizontal and vertical bounds.
 */
internal fun computeRadialArcLayout(
    itemCount: Int,
    viewport: RadialArcViewport,
    spec: RadialArcSpec,
): RadialArcLayout {
    if (itemCount <= 0 || viewport.widthPx <= 0f || viewport.heightPx <= 0f) {
        return RadialArcLayout(centerXPx = 0f, anchorCenterYPx = 0f, items = emptyList())
    }
    val centerXPx = viewport.widthPx / 2f
    val anchorCenterYPx =
        (viewport.heightPx - viewport.bottomInsetPx - spec.anchorBottomMarginPx - spec.anchorHeightPx / 2f)
            .coerceAtLeast(spec.anchorHeightPx / 2f)
    val anglesDeg = radialSpreadAngles(itemCount)
    val maxSin = anglesDeg.maxOf { angle -> abs(sin(angle.toRadians())) }
    val maxCos = anglesDeg.maxOf { angle -> cos(angle.toRadians()) }.coerceAtLeast(MIN_COSINE_DENOMINATOR)
    val maxHorizontalReach = (centerXPx - spec.edgeMarginPx - spec.itemHalfSpanPx).coerceAtLeast(0f)
    val availableVertical =
        (anchorCenterYPx - viewport.topInsetPx - spec.edgeMarginPx - spec.itemTopExtentPx)
            .coerceAtLeast(0f)
    val radiusByWidth =
        if (maxSin <= MIN_SINE_THRESHOLD) spec.preferredRadiusPx else maxHorizontalReach / maxSin
    val radiusByHeight = availableVertical / maxCos
    val effectiveRadius = minOf(spec.preferredRadiusPx, radiusByWidth, radiusByHeight).coerceAtLeast(0f)
    val positions =
        anglesDeg.map { angle ->
            val rad = angle.toRadians()
            RadialArcItemPosition(
                offsetXPx = effectiveRadius * sin(rad),
                offsetYPx = -effectiveRadius * cos(rad),
            )
        }
    return RadialArcLayout(
        centerXPx = centerXPx,
        anchorCenterYPx = anchorCenterYPx,
        items = positions,
    )
}

private fun radialSpreadAngles(itemCount: Int): List<Float> = when (itemCount) {
    1 -> listOf(0f)

    RADIAL_PAIR_COUNT -> listOf(-RADIAL_TWO_ITEM_SPREAD_DEG, RADIAL_TWO_ITEM_SPREAD_DEG)

    RADIAL_TRIAD_COUNT -> listOf(-RADIAL_THREE_ITEM_SPREAD_DEG, 0f, RADIAL_THREE_ITEM_SPREAD_DEG)

    else ->
        List(itemCount) { index ->
            -RADIAL_MULTI_ITEM_MAX_SPREAD_DEG +
                index * (2f * RADIAL_MULTI_ITEM_MAX_SPREAD_DEG) / (itemCount - 1)
        }
}

private fun Float.toRadians(): Float = (this * DEGREES_TO_RADIANS).toFloat()

private object FullWindowPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

@Composable
internal fun RadialSubActionOverlay(
    subActions: ImmutableList<ActionItemUi>,
    visible: Boolean,
    canInteract: Boolean,
    originInScreen: Offset?,
    onSelectSubAction: (ActionItemUi) -> Unit,
    onDismiss: () -> Unit,
    onDismissSettled: () -> Unit,
) {
    val windowSizePx = LocalWindowInfo.current.containerSize
    val arcLayout = rememberRadialArcLayout(subActionCount = subActions.size, windowSizePx = windowSizePx)
    val spatialProgress = remember { Animatable(0f) }
    val effectsAlpha = remember { Animatable(0f) }
    val currentMotionScheme by rememberUpdatedState(LocalLomoMotion.current.component)
    val currentOnDismissSettled by rememberUpdatedState(onDismissSettled)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnSelectSubAction by rememberUpdatedState(onSelectSubAction)
    LaunchedEffect(visible) {
        runRadialOverlayTransition(
            visible = visible,
            spatialProgress = spatialProgress,
            effectsAlpha = effectsAlpha,
            motionScheme = currentMotionScheme,
            onDismissSettled = { currentOnDismissSettled() },
        )
    }

    Popup(
        popupPositionProvider = FullWindowPopupPositionProvider,
        onDismissRequest = { if (canInteract) currentOnDismiss() },
        properties =
        PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        RadialOverlayContent(
            windowSizePx = windowSizePx,
            arcLayout = arcLayout,
            subActions = subActions,
            originInScreen = originInScreen,
            spatialProgress = spatialProgress.value,
            effectsAlpha = effectsAlpha.value,
            onSelectSubAction = { subAction -> if (canInteract) currentOnSelectSubAction(subAction) },
            onDismiss = { if (canInteract) currentOnDismiss() },
        )
    }
}

@Composable
private fun rememberRadialArcLayout(subActionCount: Int, windowSizePx: IntSize): RadialArcLayout {
    val density = LocalDensity.current
    val safeDrawing = WindowInsets.safeDrawing
    val viewport =
        remember(windowSizePx, density, safeDrawing) {
            RadialArcViewport(
                widthPx = windowSizePx.width.toFloat(),
                heightPx = windowSizePx.height.toFloat(),
                topInsetPx = safeDrawing.getTop(density).toFloat(),
                bottomInsetPx = safeDrawing.getBottom(density).toFloat(),
            )
        }
    val preferredRadiusDp =
        if (subActionCount <= RADIAL_PAIR_COUNT) {
            ActionSheetTokens.RadialTwoItemRadius
        } else {
            ActionSheetTokens.RadialThreeItemRadius
        }
    val spec =
        with(density) {
            RadialArcSpec(
                anchorBottomMarginPx = ActionSheetTokens.RadialAnchorBottomMargin.toPx(),
                anchorHeightPx = ActionSheetTokens.RadialAnchorHeight.toPx(),
                itemHalfSpanPx = (ActionSheetTokens.RadialItemSpanWidth / 2).toPx(),
                itemTopExtentPx = ActionSheetTokens.RadialItemTopExtent.toPx(),
                itemBottomExtentPx = ActionSheetTokens.RadialItemBottomExtent.toPx(),
                preferredRadiusPx = preferredRadiusDp.toPx(),
                edgeMarginPx = ActionSheetTokens.RadialEdgeMargin.toPx(),
            )
        }
    return remember(subActionCount, viewport, spec) {
        computeRadialArcLayout(itemCount = subActionCount, viewport = viewport, spec = spec)
    }
}

@Composable
private fun RadialOverlayContent(
    windowSizePx: IntSize,
    arcLayout: RadialArcLayout,
    subActions: ImmutableList<ActionItemUi>,
    originInScreen: Offset?,
    spatialProgress: Float,
    effectsAlpha: Float,
    onSelectSubAction: (ActionItemUi) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    var overlayScreenOrigin by remember { mutableStateOf<Offset?>(null) }
    val currentOverlayOrigin = overlayScreenOrigin
    val originInOverlay =
        if (originInScreen != null && currentOverlayOrigin != null) {
            originInScreen - currentOverlayOrigin
        } else {
            null
        }
    val boxWidthDp = with(density) { windowSizePx.width.toDp() }
    val boxHeightDp = with(density) { windowSizePx.height.toDp() }
    Box(
        modifier =
        Modifier
            .width(boxWidthDp)
            .height(boxHeightDp)
            .benchmarkAnchorRoot()
            .onGloballyPositioned { coords ->
                overlayScreenOrigin = coords.localToScreen(Offset.Zero)
            },
    ) {
        RadialOverlayScrim(effectsAlpha = effectsAlpha, onDismiss = onDismiss)
        RadialCloseAnchorPill(
            centerXPx = arcLayout.centerXPx,
            centerYPx = arcLayout.anchorCenterYPx,
            spatialProgress = spatialProgress,
            effectsAlpha = effectsAlpha,
            onDismiss = onDismiss,
        )
        val startOrigin = originInOverlay ?: Offset(arcLayout.centerXPx, arcLayout.anchorCenterYPx)
        arcLayout.items.forEachIndexed { index, position ->
            val targetCenter =
                Offset(arcLayout.centerXPx + position.offsetXPx, arcLayout.anchorCenterYPx + position.offsetYPx)
            val animatedCenter =
                Offset(
                    x = lerp(startOrigin.x, targetCenter.x, spatialProgress),
                    y = lerp(startOrigin.y, targetCenter.y, spatialProgress),
                )
            RadialSubActionItem(
                action = subActions[index],
                centerInOverlay = animatedCenter,
                spatialProgress = spatialProgress,
                effectsAlpha = effectsAlpha,
                onClick = { onSelectSubAction(subActions[index]) },
            )
        }
    }
}

@Composable
private fun RadialOverlayScrim(effectsAlpha: Float, onDismiss: () -> Unit) {
    val hazeState = LocalLomoHazeState.current
    val blurSpec = resolveRadialOverlayBlurSpec(effectsAlpha = effectsAlpha, colorScheme = MaterialTheme.colorScheme)
    val scrimModifier =
        if (hazeState != null) {
            Modifier.hazeBlur(
                input = HazeInput.Sources(hazeState),
                style =
                HazeBlurStyle {
                    blurRadius(blurSpec.blurRadius)
                    alpha(blurSpec.alpha)
                    colorEffects(listOf(HazeColorEffect.tint(blurSpec.tintColor)))
                    fallbackColorEffect(HazeColorEffect.tint(blurSpec.tintColor))
                },
            )
        } else {
            Modifier.background(blurSpec.fallbackTintColor)
        }
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .then(scrimModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RadialCloseAnchorPill(
    centerXPx: Float,
    centerYPx: Float,
    spatialProgress: Float,
    effectsAlpha: Float,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val halfWidthPx = with(density) { (ActionSheetTokens.RadialAnchorWidth / 2).toPx() }
    val halfHeightPx = with(density) { (ActionSheetTokens.RadialAnchorHeight / 2).toPx() }
    val colorScheme = MaterialTheme.colorScheme
    LomoFilledTonalButton(
        onClick = onDismiss,
        shapes = ButtonShapes(shape = CircleShape, pressedShape = AppShapes.Large),
        colors =
        ButtonDefaults.filledTonalButtonColors(
            containerColor = colorScheme.primaryContainer,
            contentColor = colorScheme.onPrimaryContainer,
        ),
        contentPadding = PaddingValues(0.dp),
        modifier =
        Modifier
            .width(ActionSheetTokens.RadialAnchorWidth)
            .height(ActionSheetTokens.RadialAnchorHeight)
            .offset {
                IntOffset(
                    x = (centerXPx - halfWidthPx).roundToInt(),
                    y = (centerYPx - halfHeightPx).roundToInt(),
                )
            }
            .graphicsLayer {
                alpha = effectsAlpha.coerceIn(0f, 1f)
                val scale = (RADIAL_ANCHOR_MIN_SCALE + RADIAL_ANCHOR_SCALE_DELTA * spatialProgress).coerceAtLeast(0f)
                scaleX = scale
                scaleY = scale
            },
    ) {
        LomoIcon(
            icon = LomoIcons.Close,
            contentDescription = stringResource(Res.string.cd_close_action_group),
            modifier = Modifier.size(ActionSheetTokens.RadialIconSize),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RadialSubActionItem(
    action: ActionItemUi,
    centerInOverlay: Offset,
    spatialProgress: Float,
    effectsAlpha: Float,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val itemHalfSpanPx = with(density) { (ActionSheetTokens.RadialItemSpanWidth / 2).toPx() }
    val circleRadiusPx = with(density) { (ActionSheetTokens.RadialButtonSize / 2).toPx() }
    val colorScheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
        Modifier
            .width(ActionSheetTokens.RadialItemSpanWidth)
            .offset {
                IntOffset(
                    x = (centerInOverlay.x - itemHalfSpanPx).roundToInt(),
                    y = (centerInOverlay.y - circleRadiusPx).roundToInt(),
                )
            }
            .graphicsLayer {
                alpha = effectsAlpha.coerceIn(0f, 1f)
                val scale = (RADIAL_ITEM_MIN_SCALE + RADIAL_ITEM_SCALE_DELTA * spatialProgress).coerceAtLeast(0f)
                scaleX = scale
                scaleY = scale
            },
    ) {
        LomoFilledTonalIconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            colors =
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = colorScheme.surfaceContainerHighest,
                contentColor = colorScheme.onSurface,
            ),
            modifier =
            Modifier
                .size(ActionSheetTokens.RadialButtonSize)
                .benchmarkAnchor(action.benchmarkTag),
        ) {
            LomoIcon(
                icon = action.icon,
                contentDescription = action.label,
                modifier = Modifier.size(ActionSheetTokens.RadialIconSize),
            )
        }
        Spacer(modifier = Modifier.height(ActionSheetTokens.RadialLabelTopSpacing))
        Surface(
            shape = CircleShape,
            color = ActionSheetTokens.radialLabelContainerColor(colorScheme),
            contentColor = colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
        ) {
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                Modifier.padding(
                    horizontal = ActionSheetTokens.RadialLabelHorizontalPadding,
                    vertical = ActionSheetTokens.RadialLabelVerticalPadding,
                ),
            )
        }
    }
}
