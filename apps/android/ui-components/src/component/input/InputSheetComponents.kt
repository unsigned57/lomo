package com.lomo.ui.component.input

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.component.common.lomoCompactInteractiveHeight
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.cd_collapse
import com.lomo.ui.generated.resources.cd_expand
import com.lomo.ui.generated.resources.input_mode_edit
import com.lomo.ui.generated.resources.input_mode_preview
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.AppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Natural text height reported by the field; the morph starts from it. Zero before the first layout. */
@Stable
private class InputEditorTextMetrics {
    var contentHeightPx by mutableIntStateOf(0)
}

@Composable
internal fun InputEditorPanel(
    presentation: InputEditorPanelState,
    callbacks: InputEditorPanelCallbacks,
    benchmarkTags: InputSheetBenchmarkTags,
    haptic: AppHapticFeedback,
) {
    val motion = presentation.motion
    val expansion = motion.expansion
    val inputValue = presentation.inputValue
    val hintText = presentation.hintText
    val visibleText =
        remember(inputValue.text, presentation.surface.hiddenTextRanges) {
            inputEditorVisibleText(inputValue.text, presentation.surface.hiddenTextRanges)
        }
    val (inputTextStyle, hintTextStyle) = rememberInputEditorTextStyles(visibleText, hintText)
    val chrome =
        rememberInputEditorChrome(
            isExpanded = presentation.surface.isExpanded,
            displayMode = motion.displayMode,
            inputText = visibleText,
            hintText = hintText,
        )
    val toolbarTools =
        rememberResolvedInputToolbarTools(
            surface = presentation.surface,
            showTagSelector = presentation.showTagSelector,
        )
    val headerEndTools = rememberInputHeaderTools(toolbarTools)
    val density = LocalDensity.current
    val canvasBounds =
        remember(inputTextStyle, density) {
            val paddingPx = with(density) { (InputSheetTokens.EditorContainerPaddingVertical * 2).roundToPx() }
            val minPx = resolveMemoInputMinimumContentHeightPx(inputTextStyle, density) + paddingPx
            val maxPx = resolveMemoInputMaximumContentHeightPx(inputTextStyle, density) + paddingPx
            InputEditorCanvasBounds(compactMinPx = minPx, compactMaxPx = maxOf(minPx, maxPx))
        }
    val textMetrics = remember { InputEditorTextMetrics() }

    InputEditorPanelLayout(
        expansion = expansion,
        canvasBounds = canvasBounds,
        textMetrics = textMetrics,
        header = {
            InputEditorHeader(
                chrome = chrome,
                expansion = expansion,
                badges = presentation.surface.actionBadges,
                headerEndTools = headerEndTools,
                callbacks = callbacks,
                benchmarkExpandTag = benchmarkTags.expand,
                haptic = haptic,
            )
        },
        canvas = {
            InputEditorCanvas(
                chrome = chrome,
                motion = motion,
                state =
                InputEditorTextFieldState(
                    showsPlaceholder = chrome.showsPlaceholder,
                    inputValue = inputValue,
                    hintText = hintText,
                    focusRequester = presentation.focusRequester,
                    textStyle = inputTextStyle,
                    placeholderTextStyle = hintTextStyle,
                    benchmarkEditorTag = benchmarkTags.editor,
                    hiddenTextRanges = presentation.surface.hiddenTextRanges,
                    inputState = presentation.surface.inputState,
                    inputTransformation = presentation.surface.inputTransformation,
                ),
                previewState = presentation.surface.previewState,
                onContentHeightChanged = { textMetrics.contentHeightPx = it },
            )
        },
        footer = {
            InputEditorFooter(
                presentation = presentation,
                callbacks = callbacks,
                chrome = chrome,
                toolbarTools = toolbarTools,
                benchmarkSubmitTag = benchmarkTags.submit,
                haptic = haptic,
            )
        },
    )
}

private data class InputEditorCanvasBounds(val compactMinPx: Int, val compactMaxPx: Int)

/**
 * Header, canvas and footer stacked by one measure policy that reads the live expansion: the canvas
 * travels from its compact height to the remaining space and publishes that travel as the drag span.
 * Motion only re-runs this layout; nothing recomposes while the sheet morphs.
 */
@Composable
private fun InputEditorPanelLayout(
    expansion: InputSheetExpansionState,
    canvasBounds: InputEditorCanvasBounds,
    textMetrics: InputEditorTextMetrics,
    header: @Composable () -> Unit,
    canvas: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    val measurePolicy =
        remember(expansion, canvasBounds, textMetrics) {
            MultiContentMeasurePolicy { (headerSlot, canvasSlot, footerSlot), constraints ->
                val horizontalPx = InputSheetTokens.PanelHorizontalPadding.roundToPx()
                val gapPx = InputSheetTokens.HeaderCanvasGap.roundToPx()
                val bottomPx = InputSheetTokens.PanelBottomPadding.roundToPx()
                val innerWidth = (constraints.maxWidth - horizontalPx * 2).coerceAtLeast(0)
                val slotConstraints =
                    Constraints(minWidth = innerWidth, maxWidth = innerWidth, maxHeight = constraints.maxHeight)
                val headers = headerSlot.map { it.measure(slotConstraints) }
                val footers = footerSlot.map { it.measure(slotConstraints) }
                val chromeHeight = headers.sumOf { it.height } + gapPx + footers.sumOf { it.height } + bottomPx
                check(constraints.hasBoundedHeight) { "The input sheet panel morphs inside its bounded window" }
                val availablePx = constraints.maxHeight - chromeHeight
                // Read during the morph so a text change re-runs this layout with the new natural height.
                val contentPx = textMetrics.contentHeightPx
                val range =
                    resolveInputEditorCanvasHeightRange(
                        expansion = expansion.fraction,
                        compactMinPx = canvasBounds.compactMinPx,
                        compactMaxPx = canvasBounds.compactMaxPx,
                        contentPx = contentPx,
                        availablePx = availablePx,
                    )
                expansion.dragSpanPx =
                    resolveInputEditorCanvasTravelPx(
                        compactMinPx = canvasBounds.compactMinPx,
                        compactMaxPx = canvasBounds.compactMaxPx,
                        contentPx = contentPx,
                        availablePx = availablePx,
                    ).toFloat()
                val canvases =
                    canvasSlot.map {
                        it.measure(
                            Constraints(
                                minWidth = innerWidth,
                                maxWidth = innerWidth,
                                minHeight = range.minPx,
                                maxHeight = range.maxPx,
                            ),
                        )
                    }
                val canvasHeight = canvases.maxOfOrNull { it.height } ?: 0
                val height = (chromeHeight + canvasHeight).coerceIn(constraints.minHeight, constraints.maxHeight)
                layout(constraints.maxWidth, height) {
                    var y = 0
                    headers.forEach { placeable ->
                        placeable.place(horizontalPx, y)
                        y += placeable.height
                    }
                    y += gapPx
                    canvases.forEach { placeable -> placeable.place(horizontalPx, y) }
                    y += canvasHeight
                    footers.forEach { placeable ->
                        placeable.place(horizontalPx, y)
                        y += placeable.height
                    }
                }
            }
        }
    Layout(
        contents = listOf(header, canvas, footer),
        modifier = Modifier.fillMaxWidth(),
        measurePolicy = measurePolicy,
    )
}

/**
 * The grip, the mode switch and the action cluster. The whole strip is the drag surface: dragging up
 * opens the long form, dragging down closes it, and a release settles by fling or position.
 */
@Composable
private fun InputEditorHeader(
    chrome: InputEditorChrome,
    expansion: InputSheetExpansionState,
    badges: ImmutableList<InputEditorActionBadge>,
    headerEndTools: ImmutableList<InputToolbarTool>,
    callbacks: InputEditorPanelCallbacks,
    benchmarkExpandTag: String?,
    haptic: AppHapticFeedback,
) {
    val layoutMotion = LocalLomoMotion.current.layout
    val velocityThreshold = with(LocalDensity.current) { InputSheetTokens.SettleVelocityThreshold.toPx() }
    val currentIsExpanded by rememberUpdatedState(chrome.isExpanded)
    val currentOnExpandedChange by rememberUpdatedState(callbacks.onExpandedChange)
    val longFormChromePresent by remember(expansion) { derivedStateOf { expansion.fraction > 0f } }
    val compactChromePresent by remember(expansion) { derivedStateOf { expansion.fraction < 1f } }
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .draggable(
                state = expansion,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val expand =
                        resolveInputSheetSettleTarget(
                            fraction = expansion.fraction,
                            velocityPxPerSecond = velocity,
                            velocityThresholdPxPerSecond = velocityThreshold,
                        )
                    if (expand != currentIsExpanded) currentOnExpandedChange(expand)
                    expansion.animateTo(
                        expanded = expand,
                        animationSpec = layoutMotion.defaultSpatialSpec(),
                        initialVelocity = expansion.fractionVelocityOf(velocity),
                    )
                },
            ),
    ) {
        InputSheetHeaderTopInset(expansion = { expansion.fraction })
        // Start content, the drag handle and the action cluster share one row: the handle sits
        // centered between two equal slots, so a badge or the mode switch can never run under it.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (longFormChromePresent) {
                    Box(modifier = Modifier.revealFromStart { expansion.fraction }) {
                        InputEditorDisplayModeSwitch(
                            displayMode = chrome.displayMode,
                            enabled = chrome.previewToggleInteractive,
                            onDisplayModeChange = callbacks.onDisplayModeChange,
                            onCollapse = { callbacks.onExpandedChange(false) },
                        )
                    }
                }
                if (compactChromePresent && badges.isNotEmpty()) {
                    Box(modifier = Modifier.revealFromTop { 1f - expansion.fraction }) {
                        InputEditorActionBadgesContent(badges = badges, onEditorCommand = callbacks.onEditorCommand)
                    }
                }
            }
            InputSheetHandleSlot(expansion = { expansion.fraction })
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopEnd) {
                InputEditorActionCluster(
                    headerEndTools = headerEndTools,
                    toolsEnabled = chrome.formattingInteractive,
                    isExpanded = chrome.isExpanded,
                    expansion = { expansion.fraction },
                    onToggleExpanded = { callbacks.onExpandedChange(!chrome.isExpanded) },
                    onEditorCommand = callbacks.onEditorCommand,
                    benchmarkExpandTag = benchmarkExpandTag,
                    haptic = haptic,
                )
            }
        }
        // The long form has room for a dedicated metadata row under the mode switch.
        if (longFormChromePresent && badges.isNotEmpty()) {
            Box(modifier = Modifier.revealFromTop { expansion.fraction }) {
                Column {
                    Spacer(modifier = Modifier.height(AppSpacing.ExtraSmall))
                    InputEditorActionBadgesContent(badges = badges, onEditorCommand = callbacks.onEditorCommand)
                }
            }
        }
    }
}

/**
 * Undo, redo and the form toggle as plain header icons: they belong to the sheet's own chrome, so they
 * carry no container of their own. A hairline separates history from the form toggle.
 */
@Composable
private fun InputEditorActionCluster(
    headerEndTools: ImmutableList<InputToolbarTool>,
    toolsEnabled: Boolean,
    isExpanded: Boolean,
    expansion: () -> Float,
    onToggleExpanded: () -> Unit,
    onEditorCommand: (InputEditorCommand) -> Unit,
    benchmarkExpandTag: String?,
    haptic: AppHapticFeedback,
) {
    Row(
        modifier = Modifier.heightIn(min = InputSheetTokens.HeaderActionClusterHeight),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        headerEndTools.forEach { tool ->
            InputHeaderToolButton(
                tool = tool,
                enabled = toolsEnabled && tool.enabled,
                onToolClick = { clicked -> onEditorCommand(clicked.command) },
                haptic = haptic,
            )
        }
        if (headerEndTools.isNotEmpty()) {
            VerticalDivider(
                modifier =
                Modifier
                    .padding(horizontal = AppSpacing.ExtraSmall)
                    .height(InputSheetTokens.HeaderDividerHeight),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        InputEditorFormToggleButton(
            isExpanded = isExpanded,
            expansion = expansion,
            onClick = onToggleExpanded,
            benchmarkTag = benchmarkExpandTag,
            haptic = haptic,
        )
    }
}

/** One toggle for both directions: its two glyphs turn into each other on the expansion itself. */
@Composable
private fun InputEditorFormToggleButton(
    isExpanded: Boolean,
    expansion: () -> Float,
    onClick: () -> Unit,
    benchmarkTag: String?,
    haptic: AppHapticFeedback,
) {
    val description = stringResource(if (isExpanded) Res.string.cd_collapse else Res.string.cd_expand)
    val expandTint = MaterialTheme.colorScheme.onSurfaceVariant
    val collapseTint = MaterialTheme.colorScheme.primary
    LomoIconButton(
        modifier =
        Modifier
            .benchmarkAnchor(benchmarkTag)
            .lomoCompactInteractiveHeight(InputSheetTokens.HeaderIconButtonHeight)
            .size(
                width = InputSheetTokens.HeaderIconButtonWidth,
                height = InputSheetTokens.HeaderIconButtonHeight,
            ),
        onClick = {
            haptic.medium()
            onClick()
        },
    ) {
        Box(
            modifier =
            Modifier
                .size(InputSheetTokens.HeaderIconSize)
                .semantics { contentDescription = description },
        ) {
            Icon(
                imageVector = Icons.Rounded.OpenInFull,
                contentDescription = null,
                tint = expandTint,
                modifier =
                Modifier.matchParentSize().graphicsLayer {
                    val fraction = expansion().coerceIn(0f, 1f)
                    alpha = 1f - fraction
                    rotationZ = INPUT_TOGGLE_TURN_DEGREES * fraction
                },
            )
            Icon(
                imageVector = Icons.Rounded.CloseFullscreen,
                contentDescription = null,
                tint = collapseTint,
                modifier =
                Modifier.matchParentSize().graphicsLayer {
                    val fraction = expansion().coerceIn(0f, 1f)
                    alpha = fraction
                    rotationZ = INPUT_TOGGLE_TURN_DEGREES * (fraction - 1f)
                },
            )
        }
    }
}

private const val INPUT_TOGGLE_TURN_DEGREES = 90f
private const val REVEAL_INITIAL_SCALE = 0.85f
private const val REVEAL_PIVOT_Y = 0.5f

@Composable
private fun InputEditorCanvas(
    chrome: InputEditorChrome,
    motion: InputSheetMotion,
    state: InputEditorTextFieldState,
    previewState: com.lomo.ui.component.markdown.MarkdownRenderState,
    onContentHeightChanged: (Int) -> Unit,
) {
    val displayProgress = motion.displayProgress
    val previewPresent by remember(displayProgress) { derivedStateOf { displayProgress.value > 0f } }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = InputSheetTokens.EditorCanvasShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(propagateMinConstraints = true) {
            InputEditorTextField(
                enabled = chrome.editorAcceptsFocus,
                state = state,
                placeholderAlpha = { chrome.hintAlpha(motion.expansion.fraction) },
                onContentHeightChanged = onContentHeightChanged,
                modifier = Modifier.graphicsLayer { alpha = 1f - displayProgress.value },
            )
            if (previewPresent || chrome.displayMode == InputEditorDisplayMode.Preview) {
                InputEditorPreviewContent(
                    inputText = state.inputValue.text,
                    renderState = previewState,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = displayProgress.value },
                )
            }
        }
    }
}

/** Grows from the start edge to its natural size while fading in; used for the long-form mode switch. */
private fun Modifier.revealFromStart(fraction: () -> Float): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val visible = fraction().coerceIn(0f, 1f)
        val width = (placeable.width * visible).roundToInt()
        val height = (placeable.height * visible).roundToInt()
        layout(width, height) {
            placeable.placeWithLayer(0, (height - placeable.height) / 2) {
                alpha = visible
                val scale = REVEAL_INITIAL_SCALE + (1f - REVEAL_INITIAL_SCALE) * visible
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, REVEAL_PIVOT_Y)
            }
        }
    }

/** Unrolls downward to its natural height while fading in; used for the metadata badges. */
private fun Modifier.revealFromTop(fraction: () -> Float): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val visible = fraction().coerceIn(0f, 1f)
    layout(placeable.width, (placeable.height * visible).roundToInt()) {
        placeable.placeWithLayer(0, 0) { alpha = visible }
    }
}

@Composable
private fun InputEditorDisplayModeSwitch(
    displayMode: InputEditorDisplayMode,
    enabled: Boolean,
    onDisplayModeChange: (InputEditorDisplayMode) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
        modifier
            .heightIn(min = InputSheetTokens.HeaderActionClusterHeight)
            .then(if (enabled) Modifier else Modifier.clearAndSetSemantics { }),
        shape = InputSheetTokens.SegmentedControlShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(InputSheetTokens.SegmentedControlContentPadding),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InputEditorDisplayMode.entries.forEach { mode ->
                InputEditorDisplayModePill(
                    label =
                    stringResource(
                        when (mode) {
                            InputEditorDisplayMode.Edit -> Res.string.input_mode_edit
                            InputEditorDisplayMode.Preview -> Res.string.input_mode_preview
                        },
                    ),
                    selected = displayMode == mode,
                    enabled = enabled,
                    onClick = {
                        when (val action = resolveInputEditorDisplayModeTapAction(displayMode, mode)) {
                            InputEditorDisplayModeTapAction.Collapse -> onCollapse()
                            is InputEditorDisplayModeTapAction.ChangeMode -> onDisplayModeChange(action.mode)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun InputEditorDisplayModePill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val componentMotion = LocalLomoMotion.current.component
    val containerColor by animateColorAsState(
        targetValue =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "DisplayModePillContainerColor",
    )
    val contentColor by animateColorAsState(
        targetValue =
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "DisplayModePillContentColor",
    )
    Surface(
        shape = InputSheetTokens.ModePillShape,
        color = containerColor,
        modifier =
        Modifier
            .clip(InputSheetTokens.ModePillShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            modifier = Modifier.padding(InputSheetTokens.ModePillContentPadding),
        )
    }
}
