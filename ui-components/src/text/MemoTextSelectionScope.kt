package com.lomo.ui.text

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*
import com.lomo.ui.theme.memoPlatformTextHandleColor
import com.lomo.ui.util.copyPlainTextAsync

/**
 * A registered selectable block inside a [MemoTextSelectionScope]. The
 * registrar uses the block's [LayoutCoordinates] to translate between the
 * scope's coordinate space and the block's own local coordinates so it can
 * dispatch drag handles across paragraph breaks.
 */
internal data class MemoTextSelectionScopeBlock(
    val key: Any,
    val text: String,
    val coordinates: LayoutCoordinates,
    val layout: MemoTextLayout,
    val measurer: MemoTextMeasurer,
    val baseLetterSpacingPx: Float,
)

internal enum class MemoTextSelectionEndpointKind {
    Anchor,
    Focus,
}

internal data class MemoTextSelectionScopeHandles(
    val anchorInScope: Offset,
    val focusInScope: Offset,
)

/**
 * Tracks every selectable block inside the surrounding card / overlay and
 * coordinates the shared selection state across them. Blocks register
 * themselves when their layout coordinates settle and unregister when they
 * leave composition.
 *
 * Block storage is intentionally **non-observable** — `onGloballyPositioned`
 * fires on every layout pass and would otherwise trigger a recompose cascade
 * through the scope that prevents inner BoxWithConstraints layouts (memo card
 * collapsed summary, markdown paragraphs) from settling. Only the live
 * selection and scope-root coordinates participate in recomposition; handle
 * positions are recomputed lazily from the latest registered coordinates each
 * time the selection state changes.
 */
internal class MemoTextSelectionRegistrar {
    private val blocksByKey: MutableMap<Any, MemoTextSelectionScopeBlock> = LinkedHashMap()

    var scopeCoordinates: LayoutCoordinates? by mutableStateOf(null)

    var selection: MemoMultiParagraphSelection by mutableStateOf(MemoMultiParagraphSelection.None)
        private set

    val hasSelection: Boolean
        get() = selection.hasSelection(blockOrder())

    fun register(block: MemoTextSelectionScopeBlock) {
        blocksByKey[block.key] = block
    }

    fun unregister(blockKey: Any) {
        blocksByKey.remove(blockKey)
    }

    fun beginSelection(blockKey: Any, range: IntRange) {
        selection =
            MemoMultiParagraphSelection(
                anchor = MemoTextSelectionEndpoint(blockKey, range.first),
                focus = MemoTextSelectionEndpoint(blockKey, range.last + 1),
            )
    }

    fun dragEndpoint(endpoint: MemoTextSelectionEndpointKind, scopePosition: Offset) {
        val resolved = endpointAtScopePosition(scopePosition) ?: return
        selection = when (endpoint) {
            MemoTextSelectionEndpointKind.Anchor -> selection.copy(anchor = resolved)
            MemoTextSelectionEndpointKind.Focus -> selection.copy(focus = resolved)
        }
    }

    fun clear() {
        selection = MemoMultiParagraphSelection.None
    }

    fun blockOrder(): List<Any> {
        val registrationOrder = blocksByKey.keys.toList()
        if (registrationOrder.size <= 1) return registrationOrder
        val scopeCoords = scopeCoordinates
        if (scopeCoords == null || !scopeCoords.isAttached) return registrationOrder
        val yByKey = mutableMapOf<Any, Float>()
        for ((key, block) in blocksByKey) {
            if (!block.coordinates.isAttached) continue
            yByKey[key] = scopeCoords.localPositionOf(block.coordinates, Offset.Zero).y
        }
        return resolveMemoBlockOrderByY(registrationOrder = registrationOrder, yByKey = yByKey)
    }

    fun rangeForBlock(blockKey: Any): IntRange? {
        val block = blocksByKey[blockKey] ?: return null
        return selection.selectedRangeForBlock(
            blockKey = blockKey,
            blockTextLength = block.text.length,
            blockOrder = blockOrder(),
        )
    }

    fun selectedTextOrNull(): String? {
        if (!selection.hasSelection(blockOrder())) return null
        val pairs = blocksByKey.entries.map { (key, block) -> key to block.text }
        val text = selection.selectedText(pairs)
        return text.takeIf { it.isNotEmpty() }
    }

    fun handlePositionsInScope(): MemoTextSelectionScopeHandles? {
        if (!selection.hasSelection(blockOrder())) return null
        val anchor = selection.anchor ?: return null
        val focus = selection.focus ?: return null
        val anchorScope = positionInScope(anchor) ?: return null
        val focusScope = positionInScope(focus) ?: return null
        return MemoTextSelectionScopeHandles(anchorInScope = anchorScope, focusInScope = focusScope)
    }

    private fun positionInScope(endpoint: MemoTextSelectionEndpoint): Offset? {
        val block = blocksByKey[endpoint.blockKey] ?: return null
        val scopeCoords = scopeCoordinates ?: return null
        if (!block.coordinates.isAttached || !scopeCoords.isAttached) return null
        val localPosition =
            block.layout.positionForOffset(
                endpoint.offset,
                block.measurer,
                block.baseLetterSpacingPx,
            )
        return scopeCoords.localPositionOf(block.coordinates, localPosition)
    }

    private fun endpointAtScopePosition(scopePosition: Offset): MemoTextSelectionEndpoint? {
        val scopeCoords = scopeCoordinates ?: return null
        val bounds = mutableListOf<MemoTextSelectionBlockBounds>()
        for ((key, block) in blocksByKey) {
            if (!block.coordinates.isAttached || !scopeCoords.isAttached) continue
            val topLeftScope = scopeCoords.localPositionOf(block.coordinates, Offset.Zero)
            val size = block.coordinates.size
            bounds.add(
                MemoTextSelectionBlockBounds(
                    blockKey = key,
                    topPx = topLeftScope.y,
                    bottomPx = topLeftScope.y + size.height,
                    leftPx = topLeftScope.x,
                    rightPx = topLeftScope.x + size.width,
                ),
            )
        }
        val hit = resolveMemoTextSelectionHitBlock(bounds, scopePosition) ?: return null
        val block = blocksByKey[hit.blockKey] ?: return null
        if (!block.coordinates.isAttached || !scopeCoords.isAttached) return null
        val blockLocal = block.coordinates.localPositionOf(scopeCoords, scopePosition)
        val offset = block.layout.offsetForPosition(blockLocal, block.measurer, block.baseLetterSpacingPx)
        return MemoTextSelectionEndpoint(blockKey = hit.blockKey, offset = offset)
    }
}

/**
 * Owns the cross-paragraph selection state for the memo bodies. While enabled,
 * descendant [MemoComposeParagraphText] composables register their layouts and
 * defer all selection chrome (handles and the floating copy toolbar) to this
 * scope so a long-press in one block can extend across paragraph breaks.
 *
 * The scope draws both handles at the scope root, which sits above any inner
 * memo-card content clip — that keeps the handle below a single-line memo's
 * baseline fully visible. The toolbar rect passes through
 * [LayoutCoordinates.localToRoot] so the floating action mode anchors against
 * the live selection in window space.
 *
 * The scope owns three additional dismiss paths so a started selection can be
 * abandoned without forcing a copy: system back, taps outside any registered
 * text block, and the floating toolbar's standard dismissal.
 */
@Composable
internal fun MemoTextSelectionScope(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (MemoTextSelectionRegistrar?) -> Unit,
) {
    val registrar = remember { MemoTextSelectionRegistrar() }
    val scopeModifier =
        if (enabled) {
            modifier
                .onGloballyPositioned { coordinates -> registrar.scopeCoordinates = coordinates }
                .pointerInput(registrar) {
                    detectTapGestures { if (registrar.hasSelection) registrar.clear() }
                }
        } else {
            modifier
        }
    Box(modifier = scopeModifier) {
        content(if (enabled) registrar else null)
        if (enabled) {
            MemoTextSelectionScopeChrome(registrar = registrar)
        }
    }
}

@Composable
private fun MemoTextSelectionScopeChrome(registrar: MemoTextSelectionRegistrar) {
    val handleColor = memoPlatformTextHandleColor(MaterialTheme.colorScheme)
    val textToolbar = LocalTextToolbar.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val clipboardLabel = stringResource(Res.string.clipboard_label_memo)
    val toolbarTopOffset = MEMO_TEXT_SELECTION_TOOLBAR_TOP_OFFSET
    val coroutineScope = rememberCoroutineScope()

    val selectionSnapshot = registrar.selection
    val orderSnapshot = registrar.blockOrder()
    val handles = registrar.handlePositionsInScope()
    val hasSelection = selectionSnapshot.hasSelection(orderSnapshot)
    val scopeCoords = registrar.scopeCoordinates
    val handleWindowPositions =
        if (handles != null && scopeCoords != null && scopeCoords.isAttached) {
            HandleWindowPositions(
                anchor = scopeCoords.localToWindow(handles.anchorInScope),
                focus = scopeCoords.localToWindow(handles.focusInScope),
            )
        } else {
            null
        }

    BackHandler(enabled = hasSelection) { registrar.clear() }

    LaunchedEffect(selectionSnapshot, orderSnapshot) {
        if (!hasSelection) {
            textToolbar.hide()
            return@LaunchedEffect
        }
        val scopeHandles = registrar.handlePositionsInScope() ?: run {
            textToolbar.hide()
            return@LaunchedEffect
        }
        val coords = registrar.scopeCoordinates ?: run {
            textToolbar.hide()
            return@LaunchedEffect
        }
        val anchorWindow = coords.localToRoot(scopeHandles.anchorInScope)
        val focusWindow = coords.localToRoot(scopeHandles.focusInScope)
        val toolbarTopOffsetPx = with(density) { toolbarTopOffset.toPx() }
        val left = minOf(anchorWindow.x, focusWindow.x)
        val right = maxOf(anchorWindow.x, focusWindow.x)
        val topY = minOf(anchorWindow.y, focusWindow.y)
        val bottomY = maxOf(anchorWindow.y, focusWindow.y)
        textToolbar.showMenu(
            rect =
                Rect(
                    left = left,
                    top = (topY - toolbarTopOffsetPx).coerceAtLeast(0f),
                    right = right,
                    bottom = bottomY,
                ),
            onCopyRequested = {
                val selectedText = registrar.selectedTextOrNull()
                // Clear immediately so the highlight, handles, and toolbar vanish in the same frame
                // as the tap. The clipboard write itself is async (IO dispatcher) so the IPC and
                // the system clipboard preview animation never block the main thread.
                registrar.clear()
                if (selectedText != null) {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.copyPlainTextAsync(
                        scope = coroutineScope,
                        label = clipboardLabel,
                        text = selectedText,
                    )
                }
            },
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
        )
    }

    DisposableEffect(textToolbar) {
        onDispose { textToolbar.hide() }
    }

    handleWindowPositions?.let { positions ->
        MemoTextSelectionScopeHandlePopup(
            anchorPositionInWindow = positions.anchor,
            endpoint = MemoTextSelectionEndpointKind.Anchor,
            color = handleColor,
            onDragTo = { scopePosition ->
                registrar.dragEndpoint(MemoTextSelectionEndpointKind.Anchor, scopePosition)
            },
            scopeCoordinatesProvider = { registrar.scopeCoordinates },
        )
        MemoTextSelectionScopeHandlePopup(
            anchorPositionInWindow = positions.focus,
            endpoint = MemoTextSelectionEndpointKind.Focus,
            color = handleColor,
            onDragTo = { scopePosition ->
                registrar.dragEndpoint(MemoTextSelectionEndpointKind.Focus, scopePosition)
            },
            scopeCoordinatesProvider = { registrar.scopeCoordinates },
        )
    }
}

private data class HandleWindowPositions(
    val anchor: Offset,
    val focus: Offset,
)

@Composable
private fun MemoTextSelectionScopeHandlePopup(
    anchorPositionInWindow: Offset,
    endpoint: MemoTextSelectionEndpointKind,
    color: Color,
    onDragTo: (Offset) -> Unit,
    scopeCoordinatesProvider: () -> LayoutCoordinates?,
) {
    val density = LocalDensity.current
    val touchSizePx = with(density) { MemoTextSelectionHandleTouchSize.toPx() }
    val visualSizePx = with(density) { MemoTextSelectionHandleVisualSize.toPx() }
    val currentAnchorWindow by rememberUpdatedState(anchorPositionInWindow)
    val currentOnDragTo by rememberUpdatedState(onDragTo)
    val currentScopeProvider by rememberUpdatedState(scopeCoordinatesProvider)
    val geometry = remember(endpoint, visualSizePx) { memoTextSelectionHandleGeometry(endpoint, visualSizePx) }
    val visualLeftOffsetPx = (touchSizePx - visualSizePx) / 2f

    // The popup is hosted in its own window so it isn't clipped by ancestor RoundedCornerShape
    // surfaces (Material3 Card / Surface). Position is recomputed on every recomposition through
    // the position provider that reads the live window coordinate of the selection endpoint.
    val positionProvider =
        remember(touchSizePx, visualSizePx, endpoint) {
            HandlePopupPositionProvider(
                anchorProvider = { currentAnchorWindow },
                touchSizePx = touchSizePx,
                visualSizePx = visualSizePx,
                endpoint = endpoint,
            )
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = null,
        properties =
            PopupProperties(
                focusable = false,
                clippingEnabled = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .size(MemoTextSelectionHandleTouchSize)
                    .pointerInput(Unit) {
                        var dragPositionWindow = currentAnchorWindow
                        detectDragGestures(
                            onDragStart = { dragPositionWindow = currentAnchorWindow },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragPositionWindow += dragAmount
                                val coords = currentScopeProvider() ?: return@detectDragGestures
                                if (!coords.isAttached) return@detectDragGestures
                                val scopePosition = coords.windowToLocal(dragPositionWindow)
                                currentOnDragTo(scopePosition)
                            },
                        )
                    }
                    // A pure tap on the handle (no drag) is absorbed here so it never falls through to
                    // the scope-root outside-tap clear handler. The lambda is intentionally empty.
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {})
                    },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val visualOffset = Offset(visualLeftOffsetPx, 0f)
                drawCircle(
                    color = color,
                    radius = geometry.circleRadius,
                    center = visualOffset + geometry.circleCenter,
                )
                drawRect(
                    color = color,
                    topLeft = visualOffset + Offset(geometry.shoulderRect.left, geometry.shoulderRect.top),
                    size =
                        androidx.compose.ui.geometry.Size(
                            width = geometry.shoulderRect.width,
                            height = geometry.shoulderRect.height,
                        ),
                )
            }
        }
    }
}

private class HandlePopupPositionProvider(
    private val anchorProvider: () -> Offset,
    private val touchSizePx: Float,
    private val visualSizePx: Float,
    private val endpoint: MemoTextSelectionEndpointKind,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Position the touch box so the inner visual circle's shoulder corner lands on the caret
        // point in window coordinates.
        val anchor = anchorProvider()
        return resolveMemoTextSelectionHandleTopLeft(
            anchorPositionPx = anchor,
            touchSizePx = touchSizePx,
            visualSizePx = visualSizePx,
            endpoint = endpoint,
        )
    }
}

private val MEMO_TEXT_SELECTION_TOOLBAR_TOP_OFFSET = 32.dp
