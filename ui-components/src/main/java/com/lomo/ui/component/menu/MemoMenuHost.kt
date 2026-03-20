package com.lomo.ui.component.menu

import androidx.compose.material3.ExperimentalMaterial3Api
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

    // The content
    content { state ->
        haptic.medium()
        activeState = state
    }

    activeState?.let { current ->
        MemoMenuBottomSheet(
            state = current,
            sheetState = sheetState,
            onDismissRequest = { activeState = null },
            onCopy = {
                val text = current.content
                scope.launch {
                    val clipboard =
                        androidx.core.content.ContextCompat.getSystemService(
                            context,
                            android.content.ClipboardManager::class.java,
                        )
                    val clip = android.content.ClipData.newPlainText("memo", text)
                    clipboard?.setPrimaryClip(clip)
                }
            },
            onShareImage = {
                activeState = null
                onShareImage(current)
            },
            onShareText = {
                activeState = null
                onShareText(current)
            },
            onLanShare = {
                activeState = null
                onLanShare(current)
            },
            onTogglePin =
                if (onTogglePin != null) {
                    {
                        val target = activeState
                        activeState = null
                        if (target != null) onTogglePin(target)
                    }
                } else {
                    null
                },
            onJump =
                if (onJump != null) {
                    {
                        val target = activeState
                        activeState = null
                        if (target != null) onJump(target)
                    }
                } else {
                    null
                },
            onEdit = {
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    val target = activeState
                    activeState = null
                    if (target != null) onEdit(target)
                }
            },
            onDelete = {
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    val target = activeState
                    activeState = null
                    if (target != null) onDelete(target)
                }
            },
            onHistory =
                if (onHistory != null) {
                    {
                        val target = activeState
                        activeState = null
                        if (target != null) onHistory(target)
                    }
                } else {
                    null
                },
            showHistory = showHistory,
            showJump = showJump,
        )
    }
}
