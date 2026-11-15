package com.lomo.ui.component.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import com.lomo.ui.theme.AppShapes
import dev.chrisbanes.haze.hazeSource

private const val LOMO_MODAL_SHEET_HAZE_Z_INDEX = 1f
private const val LOMO_MODAL_SHEET_HAZE_KEY = "lomo-modal-sheet"

/**
 * Canonical modal bottom sheet container shared across all Lomo sheets.
 *
 * Enforces [LomoSheetTokens] for shape, zero-elevation surfaceContainerLow background, and the
 * compact [LomoSheetDragHandle] without Material3's tooltip clickable wrapper so tapping the drag
 * handle never paints a horizontal bar ripple. Registers the entire sheet surface as a foreground
 * layer on [LocalLomoHazeState] when present.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LomoModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    dragHandle: (@Composable () -> Unit)? = { LomoSheetDragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = LomoSheetTokens.containerColor(colorScheme)
    val hazeState = LocalLomoHazeState.current
    val sheetWindowInsets = BottomSheetDefaults.modalWindowInsets
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = LomoSheetTokens.SheetShape,
        containerColor = containerColor,
        tonalElevation = LomoSheetTokens.TonalElevation,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        val hazeModifier =
            if (hazeState != null) {
                Modifier.hazeSource(
                    state = hazeState,
                    zIndex = LOMO_MODAL_SHEET_HAZE_Z_INDEX,
                    key = LOMO_MODAL_SHEET_HAZE_KEY,
                )
            } else {
                Modifier
            }
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(LomoSheetTokens.SheetShape)
                .background(containerColor)
                .then(hazeModifier)
                .windowInsetsPadding(sheetWindowInsets),
        ) {
            dragHandle?.invoke()
            content()
        }
    }
}

@Composable
fun LomoSheetDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier =
        modifier
            .fillMaxWidth()
            .padding(
                top = LomoSheetTokens.DragHandleTopPadding,
                bottom = LomoSheetTokens.DragHandleBottomPadding,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier =
            Modifier.size(
                width = LomoSheetTokens.DragHandleWidth,
                height = LomoSheetTokens.DragHandleHeight,
            ),
            shape = AppShapes.Full,
            color = LomoSheetTokens.dragHandleColor(MaterialTheme.colorScheme),
        ) {}
    }
}
