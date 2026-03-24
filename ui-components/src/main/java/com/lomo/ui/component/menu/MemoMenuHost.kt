package com.lomo.ui.component.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Encapsulates the Memo Menu BottomSheet logic.
 * @param onEdit Callback for edit action. The host handles dismissal.
 * @param onDelete Callback for delete action. The host handles dismissal.
 * @param content The screen content, which receives a `showMenu: (MemoMenuState) -> Unit` callback.
 *
 * `MemoMenuState` now carries a typed-safe `reference` (`MemoMenuReference.Id/Payload`).
 * Existing `state.memo` usage remains source-compatible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoMenuHost(
    onEdit: (MemoMenuState) -> Unit,
    onDelete: (MemoMenuState) -> Unit,
    onShareImage: (MemoMenuState) -> Unit = {},
    onShareText: (MemoMenuState) -> Unit = {},
    onLanShare: (MemoMenuState) -> Unit = {},
    onTogglePin: ((MemoMenuState) -> Unit)? = null,
    onJump: ((MemoMenuState) -> Unit)? = null,
    onHistory: ((MemoMenuState) -> Unit)? = null,
    showHistory: Boolean = false,
    showJump: Boolean = false,
    content: @Composable (showMenu: (MemoMenuState) -> Unit) -> Unit,
) {
    var activeState by remember { mutableStateOf<MemoMenuState?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    content { state ->
        haptic.medium()
        activeState = state
    }

    MemoMenuBottomSheetHost(
        current = activeState,
        context = context,
        sheetState = sheetState,
        scope = scope,
        activeStateProvider = { activeState },
        clearActiveState = { activeState = null },
        onEdit = onEdit,
        onDelete = onDelete,
        onShareImage = onShareImage,
        onShareText = onShareText,
        onLanShare = onLanShare,
        onTogglePin = onTogglePin,
        onJump = onJump,
        onHistory = onHistory,
        showHistory = showHistory,
        showJump = showJump,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoMenuBottomSheetHost(
    current: MemoMenuState?,
    context: Context,
    sheetState: SheetState,
    scope: kotlinx.coroutines.CoroutineScope,
    activeStateProvider: () -> MemoMenuState?,
    clearActiveState: () -> Unit,
    onEdit: (MemoMenuState) -> Unit,
    onDelete: (MemoMenuState) -> Unit,
    onShareImage: (MemoMenuState) -> Unit,
    onShareText: (MemoMenuState) -> Unit,
    onLanShare: (MemoMenuState) -> Unit,
    onTogglePin: ((MemoMenuState) -> Unit)?,
    onJump: ((MemoMenuState) -> Unit)?,
    onHistory: ((MemoMenuState) -> Unit)?,
    showHistory: Boolean,
    showJump: Boolean,
) {
    current?.let { state ->
        MemoMenuBottomSheet(
            state = state,
            sheetState = sheetState,
            onDismissRequest = clearActiveState,
            onCopy = { copyMemoContent(context = context, text = state.content) },
            onShareImage = {
                clearActiveState()
                onShareImage(state)
            },
            onShareText = {
                clearActiveState()
                onShareText(state)
            },
            onLanShare = {
                clearActiveState()
                onLanShare(state)
            },
            onTogglePin =
                createOptionalStateAction(
                    handler = onTogglePin,
                    activeStateProvider = activeStateProvider,
                    clearActiveState = clearActiveState,
                ),
            onJump =
                createOptionalStateAction(
                    handler = onJump,
                    activeStateProvider = activeStateProvider,
                    clearActiveState = clearActiveState,
                ),
            onEdit = {
                hideSheetAndConsumeState(
                    scope = scope,
                    sheetState = sheetState,
                    activeStateProvider = activeStateProvider,
                    clearActiveState = clearActiveState,
                    handler = onEdit,
                )
            },
            onDelete = {
                hideSheetAndConsumeState(
                    scope = scope,
                    sheetState = sheetState,
                    activeStateProvider = activeStateProvider,
                    clearActiveState = clearActiveState,
                    handler = onDelete,
                )
            },
            onHistory =
                createOptionalStateAction(
                    handler = onHistory,
                    activeStateProvider = activeStateProvider,
                    clearActiveState = clearActiveState,
                ),
            showHistory = showHistory,
            showJump = showJump,
        )
    }
}

private fun copyMemoContent(
    context: Context,
    text: String,
) {
    val clipboard =
        androidx.core.content.ContextCompat.getSystemService(
            context,
            ClipboardManager::class.java,
        )
    val clip = ClipData.newPlainText("memo", text)
    clipboard?.setPrimaryClip(clip)
}

private fun createOptionalStateAction(
    handler: ((MemoMenuState) -> Unit)?,
    activeStateProvider: () -> MemoMenuState?,
    clearActiveState: () -> Unit,
): (() -> Unit)? =
    handler?.let { resolvedHandler ->
        {
            consumeState(
                activeStateProvider = activeStateProvider,
                clearActiveState = clearActiveState,
                handler = resolvedHandler,
            )
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
private fun hideSheetAndConsumeState(
    scope: kotlinx.coroutines.CoroutineScope,
    sheetState: SheetState,
    activeStateProvider: () -> MemoMenuState?,
    clearActiveState: () -> Unit,
    handler: (MemoMenuState) -> Unit,
) {
    scope.launch { sheetState.hide() }.invokeOnCompletion {
        consumeState(
            activeStateProvider = activeStateProvider,
            clearActiveState = clearActiveState,
            handler = handler,
        )
    }
}

private fun consumeState(
    activeStateProvider: () -> MemoMenuState?,
    clearActiveState: () -> Unit,
    handler: (MemoMenuState) -> Unit,
) {
    val target = activeStateProvider()
    clearActiveState()
    if (target != null) {
        handler(target)
    }
}
