package com.lomo.ui.component.picker

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
import com.lomo.ui.util.AppHapticFeedback
import com.lomo.ui.util.LocalAppHapticFeedback
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged

private const val WHEEL_VISIBLE_ITEMS_DEFAULT = 5
private val WHEEL_ITEM_HEIGHT_DEFAULT = 40.dp
private const val WHEEL_FADE_MIN_ALPHA = 0.25f

/**
 * Snap-flinging vertical wheel that selects a value in [SecondsWheelMath.MIN]..[SecondsWheelMath.MAX].
 *
 * Behavior:
 * - The visually centered item is the current [value]. When [value] changes externally, the wheel
 *   animates to bring it to the center.
 * - User scroll / fling settles by snapping the nearest item to the center. Each time the centered
 *   second changes, [onValueChange] fires and a light haptic tick (`AppHapticFeedback.light`) is
 *   emitted.
 * - Out-of-range values are coerced via [SecondsWheelMath.clamp] (no wrap-around).
 *
 * Rounding/clamp semantics are pinned by `SecondsWheelMathTest`.
 */
@Composable
fun SecondsWheelPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleItems: Int = WHEEL_VISIBLE_ITEMS_DEFAULT,
    itemHeight: Dp = WHEEL_ITEM_HEIGHT_DEFAULT,
    wheelContentDescription: String? = null,
    haptic: AppHapticFeedback = LocalAppHapticFeedback.current,
    reduceMotion: Boolean = systemReduceMotionEnabled(),
    selectedStateDescription: (@Composable (String) -> String)? = null,
) {
    require(visibleItems % 2 == 1) { "visibleItems must be odd so an item can sit at the center" }
    val clampedInitial = SecondsWheelMath.clamp(value)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = clampedInitial)
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.roundToPx() }
    val padding = itemHeight * (visibleItems / 2)
    val locale = currentPickerLocale()
    val resolvedWheelContentDescription =
        wheelContentDescription ?: stringResource(R.string.seconds_wheel_picker_content_description)
    val currentValue by rememberUpdatedState(SecondsWheelMath.clamp(value))
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentHaptic by rememberUpdatedState(haptic)
    var previousCenteredSecond by remember { mutableIntStateOf(clampedInitial) }
    var externalSyncTarget by remember { mutableStateOf<Int?>(null) }

    val centeredSecondState = remember(itemHeightPx) {
        derivedStateOf {
            SecondsWheelMath.centeredSecond(
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleOffsetPx = listState.firstVisibleItemScrollOffset,
                itemHeightPx = itemHeightPx,
            )
        }
    }
    val centeredSecond by centeredSecondState
    val formattedCurrentSecond =
        SecondsPickerPresentationPolicy.displayText(second = currentValue, locale = locale)
    val resolvedSelectedStateDescription =
        selectedStateDescription?.invoke(formattedCurrentSecond)
            ?: stringResource(
                R.string.seconds_wheel_picker_state_description,
                formattedCurrentSecond,
            )

    LaunchedEffect(listState, itemHeightPx) {
        collectSecondsWheelSelectionChanges(
            SecondsWheelSelectionContext(
                centeredSecond = { centeredSecondState.value },
                externalSyncTarget = { externalSyncTarget },
                updateExternalSyncTarget = { externalSyncTarget = it },
                previousCenteredSecond = { previousCenteredSecond },
                updatePreviousCenteredSecond = { previousCenteredSecond = it },
                currentValue = { currentValue },
                emitValueChange = { currentOnValueChange(it) },
                emitHaptic = { currentHaptic.light() },
            ),
        )
    }

    LaunchedEffect(value, reduceMotion) {
        syncSecondsWheelExternalValue(
            target = SecondsWheelMath.clamp(value),
            reduceMotion = reduceMotion,
            context =
                SecondsWheelExternalSyncContext(
                    listState = listState,
                    centeredSecond = { centeredSecondState.value },
                    externalSyncTarget = { externalSyncTarget },
                    updateExternalSyncTarget = { externalSyncTarget = it },
                    updatePreviousCenteredSecond = { previousCenteredSecond = it },
                ),
        )
    }

    SecondsWheelPickerContent(
        viewport =
            SecondsWheelViewport(
                visibleItems = visibleItems,
                itemHeight = itemHeight,
                padding = padding,
            ),
        listState = listState,
        centeredSecond = centeredSecond,
        locale = locale,
        wheelContentDescription = resolvedWheelContentDescription,
        selectedStateDescription = resolvedSelectedStateDescription,
        currentValue = currentValue,
        onAccessibilityProgress = { requestedProgress ->
            val action =
                SecondsPickerPresentationPolicy.accessibilityProgressAction(
                    requestedProgress = requestedProgress,
                    currentValue = currentValue,
                )
            if (action.emitValueChange) {
                currentOnValueChange(action.targetSecond)
            }
            action.handled
        },
        modifier = modifier,
    )
}

@Composable
private fun SecondsWheelPickerContent(
    viewport: SecondsWheelViewport,
    listState: LazyListState,
    centeredSecond: Int,
    locale: Locale,
    wheelContentDescription: String,
    selectedStateDescription: String,
    currentValue: Int,
    onAccessibilityProgress: (Float) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(viewport.itemHeight * viewport.visibleItems)
            .clip(RoundedCornerShape(16.dp))
            .semantics(mergeDescendants = true) {
                contentDescription =
                    wheelContentDescription
                stateDescription = selectedStateDescription
                progressBarRangeInfo =
                    ProgressBarRangeInfo(
                        current = currentValue.toFloat(),
                        range = SecondsWheelMath.MIN.toFloat()..SecondsWheelMath.MAX.toFloat(),
                        steps = SecondsWheelMath.ITEM_COUNT - 2,
                    )
                setProgress { requestedProgress ->
                    onAccessibilityProgress(requestedProgress)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(viewport.itemHeight)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                ),
        )
        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(vertical = viewport.padding),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(SecondsWheelMath.ITEM_COUNT, key = { it }) { index ->
                SecondsWheelRow(
                    second = index,
                    centeredSecond = centeredSecond,
                    itemHeight = viewport.itemHeight,
                    locale = locale,
                )
            }
        }
    }
}

private data class SecondsWheelViewport(
    val visibleItems: Int,
    val itemHeight: Dp,
    val padding: Dp,
)

private data class SecondsWheelSelectionContext(
    val centeredSecond: () -> Int,
    val externalSyncTarget: () -> Int?,
    val updateExternalSyncTarget: (Int?) -> Unit,
    val previousCenteredSecond: () -> Int,
    val updatePreviousCenteredSecond: (Int) -> Unit,
    val currentValue: () -> Int,
    val emitValueChange: (Int) -> Unit,
    val emitHaptic: () -> Unit,
)

private suspend fun collectSecondsWheelSelectionChanges(context: SecondsWheelSelectionContext) {
    snapshotFlow(context.centeredSecond)
        .distinctUntilChanged()
        .collect { newValue ->
            val origin =
                if (context.externalSyncTarget() == null) {
                    SecondsPickerChangeOrigin.UserScroll
                } else {
                    SecondsPickerChangeOrigin.ExternalValueSync
                }
            val effect =
                SecondsPickerPresentationPolicy.selectionEffect(
                    origin = origin,
                    previousCenteredSecond = context.previousCenteredSecond(),
                    centeredSecond = newValue,
                    externalValue = context.currentValue(),
                )
            if (context.externalSyncTarget() == newValue) {
                context.updateExternalSyncTarget(null)
            }
            context.updatePreviousCenteredSecond(newValue)
            if (effect.emitHaptic) {
                context.emitHaptic()
            }
            if (effect.emitValueChange) {
                context.emitValueChange(newValue)
            }
        }
}

private data class SecondsWheelExternalSyncContext(
    val listState: LazyListState,
    val centeredSecond: () -> Int,
    val externalSyncTarget: () -> Int?,
    val updateExternalSyncTarget: (Int?) -> Unit,
    val updatePreviousCenteredSecond: (Int) -> Unit,
)

private suspend fun syncSecondsWheelExternalValue(
    target: Int,
    reduceMotion: Boolean,
    context: SecondsWheelExternalSyncContext,
) {
    val listState = context.listState
    if (target != listState.firstVisibleItemIndex || listState.firstVisibleItemScrollOffset != 0) {
        context.updateExternalSyncTarget(target)
        try {
            when (SecondsPickerPresentationPolicy.scrollBehavior(reduceMotion)) {
                SecondsPickerScrollBehavior.Animated -> listState.animateScrollToItem(target)
                SecondsPickerScrollBehavior.Immediate -> listState.scrollToItem(target)
            }
        } finally {
            if (context.externalSyncTarget() == target) {
                context.updatePreviousCenteredSecond(context.centeredSecond())
                context.updateExternalSyncTarget(null)
            }
        }
    }
}

@Composable
private fun SecondsWheelRow(
    second: Int,
    centeredSecond: Int,
    itemHeight: Dp,
    locale: Locale,
) {
    val distance = abs(second - centeredSecond)
    val alpha = (1f - distance * 0.35f).coerceAtLeast(WHEEL_FADE_MIN_ALPHA)
    val isCentered = distance == 0
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(itemHeight)
            .graphicsLayer { this.alpha = alpha }
            .size(width = 0.dp, height = itemHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = SecondsPickerPresentationPolicy.displayText(second = second, locale = locale),
            style = if (isCentered) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = if (isCentered) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun currentPickerLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty) Locale.ROOT else locales[0]
}

@Composable
private fun systemReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        // behavior-contract: silent-result-ok: Settings.Global key may be missing; false is the safe default
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                DEFAULT_ANIMATOR_DURATION_SCALE,
            ) == DISABLED_ANIMATOR_DURATION_SCALE
        }.getOrDefault(false)
    }
}

private const val DEFAULT_ANIMATOR_DURATION_SCALE = 1f
private const val DISABLED_ANIMATOR_DURATION_SCALE = 0f
