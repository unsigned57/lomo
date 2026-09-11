package com.lomo.ui.component.menu

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import com.lomo.ui.util.copyPlainTextAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MemoMenuActionLifecycle internal constructor(
    private val context: Context,
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

    fun copyTextAndHide(
        label: String,
        text: String,
    ) {
        copyPlainText(
            context = context,
            scope = scope,
            label = label,
            text = text,
        )
        hideAndConsume {}
    }
}

private fun copyPlainText(
    context: Context,
    scope: CoroutineScope,
    label: String,
    text: String,
) {
    val clipboard =
        androidx.core.content.ContextCompat.getSystemService(
            context,
            ClipboardManager::class.java,
        )
    clipboard?.copyPlainTextAsync(scope = scope, label = label, text = text)
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
