package com.lomo.ui.component.menu

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MemoMenuActionLifecycle internal constructor(
    private val scope: CoroutineScope,
    private val sheetState: SheetState,
    private val activeStateProvider: () -> MemoMenuState?,
    private val clearActiveState: () -> Unit,
) {
    fun dismiss() {
        clearActiveState()
    }

    fun consume(handler: (MemoMenuState) -> Unit) {
        consumeState(
            activeStateProvider = activeStateProvider,
            clearActiveState = clearActiveState,
            handler = handler,
        )
    }

    fun hideAndConsume(handler: (MemoMenuState) -> Unit) {
        hideSheetAndConsumeState(
            scope = scope,
            sheetState = sheetState,
            activeStateProvider = activeStateProvider,
            clearActiveState = clearActiveState,
            handler = handler,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun hideSheetAndConsumeState(
    scope: CoroutineScope,
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
