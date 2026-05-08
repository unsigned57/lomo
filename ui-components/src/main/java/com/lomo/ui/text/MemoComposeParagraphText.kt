package com.lomo.ui.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
import kotlin.math.roundToInt

private val SelectionToolbarCornerRadius = 8.dp
private const val SELECTION_HANDLE_STEM_WIDTH_FRACTION = 0.28f
private const val SELECTION_HANDLE_STEM_HEIGHT_FRACTION = 0.5f
private const val SELECTION_HANDLE_CIRCLE_RADIUS_FRACTION = 0.38f
private const val SELECTION_HANDLE_CIRCLE_CENTER_Y_FRACTION = 0.62f

@Composable
internal fun MemoComposeParagraphText(
    text: AnnotatedString,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    selectable: Boolean,
    selectionHighlightColor: Color,
    selectionHandleColor: Color,
    defaultLinkColor: Color,
    modifier: Modifier = Modifier,
    onTapFeedback: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val copyLabel = stringResource(R.string.action_copy)
    val clipboardLabel = stringResource(R.string.clipboard_label_memo)
    var selectionState by remember(text.text) { mutableStateOf(MemoTextSelectionState.None) }

    BoxWithConstraints(modifier = modifier) {
        val paragraphLayout =
            rememberMemoComposeParagraphLayout(
                text = text,
                style = style,
                maxLines = maxLines,
                overflow = overflow,
                widthPx = constraints.maxWidth.toFloat(),
                maxHeightPx = constraints.maxHeight,
            )
        val heightDp =
            with(LocalDensity.current) {
                paragraphLayout.layout.heightPx
                    .coerceAtLeast(paragraphLayout.lineMetrics.lineHeightPx)
                    .toDp()
            }
        Box(
            modifier =
                Modifier
                    .height(heightDp)
                    .semantics { this.text = AnnotatedString(text.text) }
                    .memoParagraphPointerInput(
                        selectable = selectable,
                        selectionState = selectionState,
                        paragraphLayout = paragraphLayout,
                        onSelectionChange = { selectionState = it },
                        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                        onTapFeedback = onTapFeedback,
                        onDoubleClick = onDoubleClick?.let { doubleClick ->
                            { _: Offset ->
                                doubleClick()
                            }
                        },
                    ),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawMemoTextLayout(
                    annotatedText = text,
                    baseStyle = style,
                    layout = paragraphLayout.layout,
                    measurer = paragraphLayout.measurer,
                    baseLetterSpacingPx = paragraphLayout.baseLetterSpacingPx,
                    selectionState = selectionState,
                    selectionHighlightColor = selectionHighlightColor,
                    defaultLinkColor = defaultLinkColor,
                )
            }
            MemoSelectionChrome(
                paragraphLayout = paragraphLayout,
                selectionState = selectionState,
                selectionHandleColor = selectionHandleColor,
                copyLabel = copyLabel,
                onCopy = {
                    val selectedText = selectionState.selectedText(text.text)
                    if (selectedText.isNotEmpty()) {
                        copyMemoTextSelection(context, clipboardLabel, selectedText)
                    }
                    selectionState = selectionState.clear()
                },
                onSelectionChange = { selectionState = it },
            )
        }
    }
}

@Composable
private fun rememberMemoComposeParagraphLayout(
    text: AnnotatedString,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    widthPx: Float,
    maxHeightPx: Int,
): MemoComposeParagraphLayout {
    val density = LocalDensity.current
    val textPaint = remember(style, density) { style.toBaseTextPaint(density) }
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
    val layout =
        remember(text, widthPx, effectiveMaxLines, overflow, lineMetrics, baseLetterSpacingPx, linkRanges, engine) {
            engine.layout(
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
                ),
            )
        }
    return MemoComposeParagraphLayout(
        layout = layout,
        measurer = measurer,
        lineMetrics = lineMetrics,
        baseLetterSpacingPx = baseLetterSpacingPx,
        linkRanges = linkRanges,
    )
}

private fun Modifier.memoParagraphPointerInput(
    selectable: Boolean,
    selectionState: MemoTextSelectionState,
    paragraphLayout: MemoComposeParagraphLayout,
    onSelectionChange: (MemoTextSelectionState) -> Unit,
    onOpenUrl: (String) -> Unit,
    onTapFeedback: (() -> Unit)?,
    onDoubleClick: ((Offset) -> Unit)?,
): Modifier =
    pointerInput(paragraphLayout, selectable, selectionState, onTapFeedback, onDoubleClick) {
        detectTapGestures(
            onPress = {
                if (!selectionState.hasSelection) {
                    onTapFeedback?.invoke()
                }
            },
            onTap = { position ->
                val offset = paragraphLayout.offsetForPosition(position)
                val link = paragraphLayout.linkRanges.firstOrNull { range -> range.contains(offset) }
                when {
                    link != null &&
                        shouldActivateMemoTextLink(selectionState, offset, paragraphLayout.linkRanges) ->
                        onOpenUrl(link.url)

                    selectionState.hasSelection -> onSelectionChange(selectionState.clear())
                }
            },
            onDoubleTap = onDoubleClick?.let { doubleClick ->
                { position ->
                    if (!selectionState.hasSelection) {
                        doubleClick(position)
                    }
                }
            },
            onLongPress = { position ->
                if (!selectable) return@detectTapGestures
                val offset = paragraphLayout.offsetForPosition(position)
                val range = paragraphLayout.layout.selectionRangeAtOffset(offset)
                onSelectionChange(
                    MemoTextSelectionState(
                        anchorOffset = range.first,
                        focusOffset = range.last + 1,
                    ),
                )
            },
        )
    }

@Composable
private fun MemoSelectionChrome(
    paragraphLayout: MemoComposeParagraphLayout,
    selectionState: MemoTextSelectionState,
    selectionHandleColor: Color,
    copyLabel: String,
    onCopy: () -> Unit,
    onSelectionChange: (MemoTextSelectionState) -> Unit,
) {
    val startOffset = selectionState.selectedAnchorOffset() ?: return
    val endOffset = selectionState.selectedFocusOffset() ?: return
    val startPosition = paragraphLayout.positionForOffset(startOffset)
    val endPosition = paragraphLayout.positionForOffset(endOffset)
    Box(modifier = Modifier.fillMaxSize()) {
        SelectionCopyToolbar(position = startPosition, copyLabel = copyLabel, onCopy = onCopy)
        SelectionHandle(
            position = startPosition,
            color = selectionHandleColor,
            onDrag = { position ->
                onSelectionChange(
                    selectionState.copy(anchorOffset = paragraphLayout.offsetForPosition(position)),
                )
            },
        )
        SelectionHandle(
            position = endPosition,
            color = selectionHandleColor,
            onDrag = { position ->
                onSelectionChange(
                    selectionState.updateFocus(paragraphLayout.offsetForPosition(position)),
                )
            },
        )
    }
}

@Composable
private fun SelectionCopyToolbar(
    position: Offset,
    copyLabel: String,
    onCopy: () -> Unit,
) {
    Surface(
        modifier =
            Modifier.offset {
                IntOffset(
                    x = position.x.roundToInt(),
                    y = (position.y - 48.dp.roundToPx()).roundToInt().coerceAtLeast(0),
                )
            },
        shape = RoundedCornerShape(SelectionToolbarCornerRadius),
        color = MaterialTheme.colorScheme.inverseSurface,
        tonalElevation = 4.dp,
    ) {
        TextButton(onClick = onCopy) {
            Text(text = copyLabel, color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

@Composable
private fun SelectionHandle(
    position: Offset,
    color: Color,
    onDrag: (Offset) -> Unit,
) {
    val handleSizePx = with(LocalDensity.current) { MemoTextSelectionHandleTouchSize.toPx() }
    Box(
        modifier =
            Modifier
                .offset {
                    resolveMemoTextSelectionHandleTopLeft(
                        anchorPosition = position,
                        handleSizePx = handleSizePx,
                    )
                }
                .size(MemoTextSelectionHandleTouchSize)
                .pointerInput(onDrag) {
                    var latestPosition = position
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        latestPosition += dragAmount
                        onDrag(latestPosition)
                    }
                },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stemWidth = size.width * SELECTION_HANDLE_STEM_WIDTH_FRACTION
            val stemHeight = size.height * SELECTION_HANDLE_STEM_HEIGHT_FRACTION
            drawRoundRect(
                color = color,
                topLeft = Offset((size.width - stemWidth) / 2f, 0f),
                size = Size(stemWidth, stemHeight),
                cornerRadius = CornerRadius(stemWidth / 2f, stemWidth / 2f),
            )
            drawCircle(
                color = color,
                radius = size.minDimension * SELECTION_HANDLE_CIRCLE_RADIUS_FRACTION,
                center = Offset(size.width / 2f, size.height * SELECTION_HANDLE_CIRCLE_CENTER_Y_FRACTION),
            )
        }
    }
}

private data class MemoComposeParagraphLayout(
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

private fun copyMemoTextSelection(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
