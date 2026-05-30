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
    val scope = selectionRegistrar
    val resolvedBlockKey = remember(blockKey) { blockKey ?: Any() }
    val customTypeface = rememberResolvedPlatformTypeface(style)
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
        Box(
            modifier =
                Modifier
                    .height(heightDp)
                    .semantics { this.text = AnnotatedString(text.text) }
                    .onGloballyPositioned(bindings.onCoordinatesChanged)
                    .memoParagraphPointerInput(
                        selectable = selectable,
                        selectionState = bindings.selectionState,
                        paragraphLayout = paragraphLayout,
                        onSelectionChange = bindings.onSelectionChange,
                        scopeHasSelection = scopeHasSelection,
                        clearScopeSelection = { scope?.clear() },
                        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                        onTapFeedback = onTapFeedback,
                        onBodyClick = onBodyClick,
                        onDoubleClick = onDoubleClick?.let { doubleClick ->
                            { _: Offset ->
                                doubleClick()
                            }
                        },
                        onLongClick = onLongClick,
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
): MemoComposeParagraphLayout {
    val density = LocalDensity.current
    val textPaint = remember(style, density, customTypeface) { style.toBaseTextPaint(density, customTypeface) }
    val measurer = remember(textPaint) { AndroidMemoTextMeasurer(textPaint) }
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
private fun rememberResolvedPlatformTypeface(style: TextStyle): android.graphics.Typeface? {
    val resolver = LocalFontFamilyResolver.current
    val resolvedTypeface =
        resolver.resolve(
            fontFamily = style.fontFamily,
            fontWeight = style.fontWeight ?: FontWeight.Normal,
            fontStyle = style.fontStyle ?: FontStyle.Normal,
            fontSynthesis = style.fontSynthesis ?: FontSynthesis.All,
        )
    return resolvedTypeface.value as? android.graphics.Typeface
}

private fun Modifier.memoParagraphPointerInput(
    selectable: Boolean,
    selectionState: MemoTextSelectionState,
    paragraphLayout: MemoComposeParagraphLayout,
    onSelectionChange: (MemoTextSelectionState) -> Unit,
    scopeHasSelection: Boolean,
    clearScopeSelection: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onTapFeedback: (() -> Unit)?,
    onBodyClick: (() -> Unit)?,
    onDoubleClick: ((Offset) -> Unit)?,
    onLongClick: (() -> Unit)?,
): Modifier =
    pointerInput(
        paragraphLayout,
        selectable,
        selectionState,
        scopeHasSelection,
        onTapFeedback,
        onBodyClick,
        onDoubleClick,
        onLongClick,
    ) {
        detectTapGestures(
            onPress = {
                if (!selectionState.hasSelection && !scopeHasSelection) {
                    onTapFeedback?.invoke()
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
                when (outcome) {
                    is MemoParagraphTapOutcome.OpenLink -> onOpenUrl(outcome.url)
                    MemoParagraphTapOutcome.ClearSelection -> {
                        // When the current paragraph owns the selection we clear via the binding so
                        // its own selectionState reflects the change; otherwise we go straight to
                        // the scope-level clear so cross-paragraph selections collapse from a tap
                        // on any block in the memo body.
                        if (selectionState.hasSelection) {
                            onSelectionChange(selectionState.clear())
                        } else {
                            clearScopeSelection()
                        }
                    }

                    MemoParagraphTapOutcome.InvokeBodyClick -> onBodyClick?.invoke()
                    MemoParagraphTapOutcome.Ignore -> Unit
                }
            },
            onDoubleTap = onDoubleClick?.let { doubleClick ->
                { position ->
                    val outcome =
                        resolveMemoParagraphDoubleTapOutcome(
                            hasDoubleClickHandler = true,
                            paragraphHasSelection = selectionState.hasSelection,
                            scopeHasSelection = scopeHasSelection,
                        )
                    when (outcome) {
                        is MemoParagraphDoubleTapOutcome.OpenEditor -> {
                            if (outcome.clearSelectionFirst) {
                                if (selectionState.hasSelection) {
                                    onSelectionChange(selectionState.clear())
                                } else {
                                    clearScopeSelection()
                                }
                            }
                            doubleClick(position)
                        }

                        MemoParagraphDoubleTapOutcome.Ignore -> Unit
                    }
                }
            },
            onLongPress = { position ->
                if (selectable) {
                    val offset = paragraphLayout.offsetForPosition(position)
                    val range = paragraphLayout.layout.selectionRangeAtOffset(offset)
                    onSelectionChange(
                        MemoTextSelectionState(
                            anchorOffset = range.first,
                            focusOffset = range.last + 1,
                        ),
                    )
                } else {
                    onLongClick?.invoke()
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
    fun offsetForPosition(position: Offset): Int =
        layout.offsetForPosition(position, measurer, baseLetterSpacingPx)

    fun positionForOffset(offset: Int): Offset =
        layout.positionForOffset(offset, measurer, baseLetterSpacingPx)
}
