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
import com.lomo.ui.util.DateTimeUtils
import com.lomo.ui.util.formatAsDateTime
import kotlinx.coroutines.launch

object MemoMenuHostDefaults {
    // Helper to format consistent with app
    fun formatTime(timestamp: Long): String = timestamp.formatAsDateTime("yyyy-MM-dd", "HH:mm")
}

/**
 * Encapsulates the Memo Menu BottomSheet logic.
 * @param onEdit Callback for edit action. The host handles dismissal.
 * @param onDelete Callback for delete action. The host handles dismissal.
 * @param content The screen content, which receives a `showMenu: (MemoMenuState) -> Unit` callback.
 *
 * Note: We pass MemoMenuState to decouple from Domain Memo object.
 * The caller is responsible for mapping their domain object to state.
 * But for convenience, maybe we can accept a generic object if we pass a mapper?
 * Let's keep it simple: caller passes MemoMenuState to showMenu.
 * Wait, showMenu needs the full state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoMenuHost(
    onEdit: (MemoMenuState) -> Unit,
    onDelete: (MemoMenuState) -> Unit,
    onShare: (MemoMenuState) -> Unit = {},
    content: @Composable (showMenu: (MemoMenuState) -> Unit) -> Unit,
) {
    var activeState by remember { mutableStateOf<MemoMenuState?>(null) }
    val sheetState = rememberModalBottomSheetState()
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
                val clipboard =
                    androidx.core.content.ContextCompat.getSystemService(
                        context,
                        android.content.ClipboardManager::class.java,
                    )
                val clip = android.content.ClipData.newPlainText("memo", current.content)
                clipboard?.setPrimaryClip(clip)
                // MemoMenuBottomSheet handles internal dismissal on copy?
                // Step 320: MemoActionSheet calls onDismiss().
                // MemoMenuBottomSheet passes onDismissRequest to onDismiss.
                // So activeState = null will be called.
            },
            onShare = {
                activeState = null
                onShare(current)
            },
            onEdit = {
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    val target = activeState
                    activeState = null // Reset state
                    if (target != null) onEdit(target)
                }
            },
            onDelete = {
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    val target = activeState
                    activeState = null // Reset state
                    if (target != null) onDelete(target)
                }
            },
        )
    }
}
