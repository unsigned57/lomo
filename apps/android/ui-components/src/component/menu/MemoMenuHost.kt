package com.lomo.ui.component.menu

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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoMenuHost(
    actions: @Composable (state: MemoMenuState, lifecycle: MemoMenuActionLifecycle) -> ImmutableList<ActionItemUi>,
    content: @Composable (showMenu: (MemoMenuState) -> Unit) -> Unit,
    onMenuCleared: () -> Unit,
    actionAutoReorderEnabled: Boolean = true,
    onActionInvoked: (String) -> Unit = {},
    onActionOrderChanged: (List<String>) -> Unit = {},
    benchmarkRootTag: String? = null,
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

    val current = activeState
    if (current != null) {
        MemoMenuBottomSheetHost(
            current = current,
            params =
                MemoMenuBottomSheetParams(
                    context = context,
                    sheetState = sheetState,
                    scope = scope,
                    activeStateProvider = { activeState },
                    clearActiveState = {
                        activeState = null
                        onMenuCleared()
                    },
                    actionAutoReorderEnabled = actionAutoReorderEnabled,
                    onActionInvoked = onActionInvoked,
                    onActionOrderChanged = onActionOrderChanged,
                    benchmarkRootTag = benchmarkRootTag,
                    actions = actions,
                ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private data class MemoMenuBottomSheetParams(
    val context: Context,
    val sheetState: SheetState,
    val scope: CoroutineScope,
    val activeStateProvider: () -> MemoMenuState?,
    val clearActiveState: () -> Unit,
    val actionAutoReorderEnabled: Boolean,
    val onActionInvoked: (String) -> Unit,
    val onActionOrderChanged: (List<String>) -> Unit,
    val benchmarkRootTag: String?,
    val actions: @Composable (state: MemoMenuState, lifecycle: MemoMenuActionLifecycle) -> ImmutableList<ActionItemUi>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoMenuBottomSheetHost(
    current: MemoMenuState,
    params: MemoMenuBottomSheetParams,
) {
    val lifecycle =
        remember(
            params.context,
            params.scope,
            params.sheetState,
            params.activeStateProvider,
            params.clearActiveState,
        ) {
            MemoMenuActionLifecycle(
                context = params.context,
                scope = params.scope,
                sheetState = params.sheetState,
                activeStateProvider = params.activeStateProvider,
                clearActiveState = params.clearActiveState,
            )
        }
    MemoMenuBottomSheet(
        state = current,
        sheetState = params.sheetState,
        onDismissRequest = params.clearActiveState,
        actions = params.actions(current, lifecycle),
        actionAutoReorderEnabled = params.actionAutoReorderEnabled,
        onActionInvoked = params.onActionInvoked,
        onActionOrderChanged = params.onActionOrderChanged,
        benchmarkRootTag = params.benchmarkRootTag,
    )
}
