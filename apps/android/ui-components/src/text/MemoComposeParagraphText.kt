package com.lomo.ui.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

@Composable
internal fun MemoComposeParagraphText(
    text: AnnotatedString,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    selectable: Boolean,
    selectionHighlightColor: Color,
    defaultLinkColor: Color,
    modifier: Modifier = Modifier,
    blockKey: Any? = null,
    selectionRegistrar: MemoTextSelectionRegistrar? = null,
    onTapFeedback: (() -> Unit)? = null,
    onBodyClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val pressHost = LocalMemoTextPressFeedback.current
    val pressTarget = remember { MemoTextPressTarget() }
    val scope = selectionRegistrar
    val resolvedBlockKey = remember(blockKey) { blockKey ?: Any() }
    val typefaceLookup = rememberMemoSpanTypefaceLookup(text, style)
    val customTypeface =
        typefaceLookup(
            style.fontFamily,
            style.fontWeight ?: FontWeight.Normal,
            style.fontStyle ?: FontStyle.Normal,
        )
    // Read scope.selection here (inside the composable) so the snapshot system propagates
    // changes to this paragraph block even when its own `rangeForBlock` would return null
    // (e.g. an out-of-selection paragraph whose only signal is the scope-level boolean).
    val scopeSelectionSnapshot = scope?.selection
    val scopeHasSelection =
        if (scope != null && scopeSelectionSnapshot != null) {
            scopeSelectionSnapshot.hasSelection(scope.blockOrder())
        } else {
            false
        }

    BoxWithConstraints(modifier = modifier) {
        val paragraphLayout =
            rememberMemoComposeParagraphLayout(
                text = text,
                style = style,
                maxLines = maxLines,
                overflow = overflow,
                widthPx = constraints.maxWidth.toFloat(),
                maxHeightPx = constraints.maxHeight,
                customTypeface = customTypeface,
                typefaceLookup = typefaceLookup,
            )
        val heightDp =
            with(LocalDensity.current) {
                paragraphLayout.layout.heightPx
                    .coerceAtLeast(paragraphLayout.lineMetrics.lineHeightPx)
                    .toDp()
            }
        val bindings =
            rememberMemoParagraphScopeBindings(
                scope = scope,
                blockKey = resolvedBlockKey,
                selectable = selectable,
                paragraphLayout = paragraphLayout,
                text = text.text,
            )
        // The gesture detector below must survive recompositions: a press already publishes
        // tap-feedback and press-state changes, so fresh callback identities here would restart
        // pointerInput mid-gesture and silently drop the tap.
        val currentCallbacks =
            rememberUpdatedState(
                MemoParagraphCallbacks(
                    onSelectionChange = bindings.onSelectionChange,
                    clearScopeSelection = { scope?.clear() },
                    onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                    onPress = { position ->
                        pressHost?.let { host -> pressTarget.press(this, host, position) }
                    },
                    onTapFeedback = onTapFeedback,
                    onBodyClick = onBodyClick,
                    onDoubleClick =
                    onDoubleClick?.let { doubleClick ->
                        { _: Offset ->
                            doubleClick()
                        }
                    },
                    onLongClick = onLongClick,
                ),
            )
        Box(
            modifier =
            Modifier
                .height(heightDp)
                .semantics { this.text = AnnotatedString(text.text) }
                .onGloballyPositioned { coordinates ->
                    pressTarget.coordinates = coordinates
                    bindings.onCoordinatesChanged(coordinates)
                }
                .memoParagraphPointerInput(
                    selectable = selectable,
                    selectionState = bindings.selectionState,
                    paragraphLayout = paragraphLayout,
                    scopeHasSelection = scopeHasSelection,
                    callbacks = currentCallbacks,
                ),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawMemoTextLayout(
                    annotatedText = text,
                    baseStyle = style,
                    layout = paragraphLayout.layout,
                    measurer = paragraphLayout.measurer,
                    baseLetterSpacingPx = paragraphLayout.baseLetterSpacingPx,
                    selectionState = bindings.selectionState,
                    selectionHighlightColor = selectionHighlightColor,
                    defaultLinkColor = defaultLinkColor,
                    customTypeface = customTypeface,
                    typefaceResolver = typefaceLookup,
                )
            }
        }
    }
}

private data class MemoParagraphScopeBindings(
    val selectionState: MemoTextSelectionState,
    val onSelectionChange: (MemoTextSelectionState) -> Unit,
    val onCoordinatesChanged: (LayoutCoordinates) -> Unit,
)

@Composable
private fun rememberMemoParagraphScopeBindings(
    scope: MemoTextSelectionRegistrar?,
    blockKey: Any,
    selectable: Boolean,
    paragraphLayout: MemoComposeParagraphLayout,
    text: String,
): MemoParagraphScopeBindings {
    val currentScope by rememberUpdatedState(scope)
    if (scope != null && selectable) {
        DisposableEffect(scope, blockKey) {
            onDispose { scope.unregister(blockKey) }
        }
    }
    val selectionRange =
        if (scope != null && selectable) {
            scope.rangeForBlock(blockKey)
        } else {
            null
        }
    val selectionState =
        if (selectionRange != null) {
            MemoTextSelectionState(
                anchorOffset = selectionRange.first,
                focusOffset = selectionRange.last + 1,
            )
        } else {
            MemoTextSelectionState.None
        }
    val onSelectionChange: (MemoTextSelectionState) -> Unit =
        remember(scope, blockKey, text) {
            change@{ next ->
                val activeScope = currentScope ?: return@change
                val range = next.selectedRange
                if (range == null) {
                    activeScope.clear()
                } else {
                    activeScope.beginSelection(blockKey, range)
                }
            }
        }
    val onCoordinatesChanged: (LayoutCoordinates) -> Unit =
        remember(scope, blockKey, paragraphLayout, text) {
            coords@{ coordinates ->
                val activeScope = currentScope ?: return@coords
                if (!selectable) return@coords
                activeScope.register(
                    MemoTextSelectionScopeBlock(
                        key = blockKey,
                        text = text,
                        coordinates = coordinates,
                        layout = paragraphLayout.layout,
                        measurer = paragraphLayout.measurer,
                        baseLetterSpacingPx = paragraphLayout.baseLetterSpacingPx,
                    ),
                )
            }
        }
    return MemoParagraphScopeBindings(
        selectionState = selectionState,
        onSelectionChange = onSelectionChange,
        onCoordinatesChanged = onCoordinatesChanged,
    )
}

@Composable
private fun rememberMemoComposeParagraphLayout(
    text: AnnotatedString,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    widthPx: Float,
    maxHeightPx: Int,
    customTypeface: android.graphics.Typeface?,
    typefaceLookup: MemoSpanTypefaceResolver,
): MemoComposeParagraphLayout {
    val density = LocalDensity.current
    val textPaint =
        remember(style, density, customTypeface, typefaceLookup) {
            style.toBaseTextPaint(density, customTypeface, typefaceLookup)
        }
    val spanCacheKey = if (text.spanStyles.isEmpty()) null else text
    val measurer =
        remember(textPaint, spanCacheKey, style, density, customTypeface, typefaceLookup) {
            if (spanCacheKey == null) {
                AndroidMemoTextMeasurer(textPaint)
            } else {
                AnnotatedMemoTextMeasurer(
                    annotatedText = spanCacheKey,
                    baseStyle = style,
                    density = density,
                    basePaint = textPaint,
                    customTypeface = customTypeface,
                    typefaceResolver = typefaceLookup,
                )
            }
        }
    val engine = remember(measurer) { MemoTextLayoutEngine(measurer) }
    val lineMetrics = remember(style, density, textPaint) { style.resolveLineMetrics(density, textPaint) }
    val baseLetterSpacingPx = remember(style, density) { style.resolveMemoLetterSpacingPx(density) }
    val linkRanges = remember(text) { text.toMemoTextLinkRanges() }
    val effectiveMaxLines =
        remember(maxLines, maxHeightPx, lineMetrics) {
            resolveMemoTextLayoutMaxLines(
                requestedMaxLines = maxLines,
                maxHeightPx = maxHeightPx,
                lineHeightPx = lineMetrics.lineHeightPx,
            )
        }
    val layoutInput =
        MemoTextLayoutInput(
            text = text.text,
            maxWidthPx = widthPx,
            fontSizePx = style.resolveFontSizePx(density),
            baseLetterSpacingPx = baseLetterSpacingPx,
            maxLines = effectiveMaxLines,
            lineHeightPx = lineMetrics.lineHeightPx,
            baselinePx = lineMetrics.baselinePx,
            protectedRanges = linkRanges.map { MemoTextProtectedRange(it.start, it.end) },
            ellipsizeLastVisibleLine =
            overflow == TextOverflow.Ellipsis ||
                effectiveMaxLines < maxLines,
        )
    val layoutCacheKey = layoutInput.toMemoTextLayoutCacheKey()
    val layout =
        remember(layoutCacheKey, engine) {
            engine.layout(layoutInput)
        }
    return MemoComposeParagraphLayout(
        layout = layout,
        measurer = measurer,
        lineMetrics = lineMetrics,
        baseLetterSpacingPx = baseLetterSpacingPx,
        linkRanges = linkRanges,
    )
}

@Composable
private fun rememberMemoSpanTypefaceLookup(text: AnnotatedString, style: TextStyle): MemoSpanTypefaceResolver {
    val resolver = LocalFontFamilyResolver.current
    val fontSynthesis = style.fontSynthesis ?: FontSynthesis.All
    val keys = remember(text, style) { collectMemoSpanTypefaceKeys(text, style) }
    val resolvedTypefaces =
        keys.associateWith { key ->
            resolver.resolve(
                fontFamily = key.fontFamily,
                fontWeight = key.fontWeight,
                fontStyle = key.fontStyle,
                fontSynthesis = fontSynthesis,
            ).value as? android.graphics.Typeface
        }
    return remember(resolvedTypefaces) {
        { fontFamily, fontWeight, fontStyle ->
            resolvedTypefaces[MemoSpanTypefaceKey(fontFamily, fontWeight, fontStyle)]
        }
    }
}

/** Interaction handlers for one memo paragraph's pointer input. */
private data class MemoParagraphCallbacks(
    val onPress: suspend androidx.compose.foundation.gestures.PressGestureScope.(Offset) -> Unit,
    val onSelectionChange: (MemoTextSelectionState) -> Unit,
    val clearScopeSelection: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onTapFeedback: (() -> Unit)?,
    val onBodyClick: (() -> Unit)?,
    val onDoubleClick: ((Offset) -> Unit)?,
    val onLongClick: (() -> Unit)?,
)

private fun Modifier.memoParagraphPointerInput(
    selectable: Boolean,
    selectionState: MemoTextSelectionState,
    paragraphLayout: MemoComposeParagraphLayout,
    scopeHasSelection: Boolean,
    callbacks: androidx.compose.runtime.State<MemoParagraphCallbacks>,
): Modifier = pointerInput(
    paragraphLayout,
    selectable,
    selectionState,
    scopeHasSelection,
    callbacks.value.onDoubleClick != null,
) {
    detectTapGestures(
        onPress = { position ->
            if (!selectionState.hasSelection && !scopeHasSelection) {
                callbacks.value.onTapFeedback?.invoke()
                callbacks.value.onPress(this, position)
            }
        },
        onTap = { position ->
            val offset = paragraphLayout.offsetForPosition(position)
            val link = paragraphLayout.linkRanges.firstOrNull { range -> range.contains(offset) }
            val outcome =
                resolveMemoParagraphTapOutcome(
                    link = link,
                    paragraphHasSelection = selectionState.hasSelection,
                    scopeHasSelection = scopeHasSelection,
                )
            val activeCallbacks = callbacks.value
            when (outcome) {
                is MemoParagraphTapOutcome.OpenLink -> activeCallbacks.onOpenUrl(outcome.url)

                MemoParagraphTapOutcome.ClearSelection -> {
                    // When the current paragraph owns the selection we clear via the binding so
                    // its own selectionState reflects the change; otherwise we go straight to
                    // the scope-level clear so cross-paragraph selections collapse from a tap
                    // on any block in the memo body.
                    if (selectionState.hasSelection) {
                        activeCallbacks.onSelectionChange(selectionState.clear())
                    } else {
                        activeCallbacks.clearScopeSelection()
                    }
                }

                MemoParagraphTapOutcome.InvokeBodyClick -> activeCallbacks.onBodyClick?.invoke()

                MemoParagraphTapOutcome.Ignore -> Unit
            }
        },
        onDoubleTap =
        callbacks.value.onDoubleClick?.let {
            { position ->
                val outcome =
                    resolveMemoParagraphDoubleTapOutcome(
                        hasDoubleClickHandler = true,
                        paragraphHasSelection = selectionState.hasSelection,
                        scopeHasSelection = scopeHasSelection,
                    )
                when (outcome) {
                    is MemoParagraphDoubleTapOutcome.OpenEditor -> {
                        val activeCallbacks = callbacks.value
                        if (outcome.clearSelectionFirst) {
                            if (selectionState.hasSelection) {
                                activeCallbacks.onSelectionChange(selectionState.clear())
                            } else {
                                activeCallbacks.clearScopeSelection()
                            }
                        }
                        activeCallbacks.onDoubleClick?.invoke(position)
                    }

                    MemoParagraphDoubleTapOutcome.Ignore -> Unit
                }
            }
        },
        onLongPress = { position ->
            if (selectable) {
                val offset = paragraphLayout.offsetForPosition(position)
                val range = paragraphLayout.layout.selectionRangeAtOffset(offset)
                callbacks.value.onSelectionChange(
                    MemoTextSelectionState(
                        anchorOffset = range.first,
                        focusOffset = range.last + 1,
                    ),
                )
            } else {
                callbacks.value.onLongClick?.invoke()
            }
        },
    )
}

internal data class MemoComposeParagraphLayout(
    val layout: MemoTextLayout,
    val measurer: MemoTextMeasurer,
    val lineMetrics: MemoLineMetrics,
    val baseLetterSpacingPx: Float,
    val linkRanges: List<MemoTextLinkRange>,
) {
    fun offsetForPosition(position: Offset): Int = layout.offsetForPosition(position, measurer, baseLetterSpacingPx)

    fun positionForOffset(offset: Int): Offset = layout.positionForOffset(offset, measurer, baseLetterSpacingPx)
}
