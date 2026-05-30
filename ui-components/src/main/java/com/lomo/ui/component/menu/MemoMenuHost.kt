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

    MemoMenuBottomSheetHost(
        current = activeState,
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
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoMenuBottomSheetHost(
    current: MemoMenuState?,
    context: Context,
    sheetState: SheetState,
    scope: CoroutineScope,
    activeStateProvider: () -> MemoMenuState?,
    clearActiveState: () -> Unit,
    actionAutoReorderEnabled: Boolean,
    onActionInvoked: (String) -> Unit,
    onActionOrderChanged: (List<String>) -> Unit,
    benchmarkRootTag: String?,
    actions: @Composable (state: MemoMenuState, lifecycle: MemoMenuActionLifecycle) -> ImmutableList<ActionItemUi>,
) {
    current?.let { state ->
        val lifecycle =
            remember(context, scope, sheetState, activeStateProvider, clearActiveState) {
                MemoMenuActionLifecycle(
                    context = context,
                    scope = scope,
                    sheetState = sheetState,
                    activeStateProvider = activeStateProvider,
                    clearActiveState = clearActiveState,
                )
            }
        MemoMenuBottomSheet(
            state = state,
            sheetState = sheetState,
            onDismissRequest = clearActiveState,
            actions = actions(state, lifecycle),
            actionAutoReorderEnabled = actionAutoReorderEnabled,
            onActionInvoked = onActionInvoked,
            onActionOrderChanged = onActionOrderChanged,
            benchmarkRootTag = benchmarkRootTag,
        )
    }
}
