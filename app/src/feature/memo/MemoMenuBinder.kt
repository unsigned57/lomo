package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.component.menu.MemoMenuHost

@Composable
fun MemoMenuBinder(
    commandHandler: MemoMenuCommandHandler,
    content: @Composable (showMenu: (MemoMenuSelection) -> Unit) -> Unit,
) {
    val presentationState = commandHandler.presentationState
    var activeSelection by remember { mutableStateOf<MemoMenuSelection?>(null) }

    MemoMenuHost(
        actions = { state, lifecycle ->
            val selection =
                checkNotNull(activeSelection?.takeIf { selection -> selection.state == state }) {
                    "MemoMenuBinder requires an active app-owned memo selection for every visible menu."
                }
            rememberMemoMenuActions(
                selection = selection,
                lifecycle = lifecycle,
                commandHandler = commandHandler,
            )
        },
        content = { showMenu ->
            content { selection ->
                activeSelection = selection
                showMenu(selection.state)
            }
        },
        onMenuCleared = { activeSelection = null },
        actionAutoReorderEnabled = presentationState.memoActionAutoReorderEnabled,
        onActionInvoked = { key -> MemoActionId.fromStorageKey(key)?.let(commandHandler::recordActionUsage) },
        onActionOrderChanged = { keys ->
            commandHandler.changeActionOrder(keys.mapNotNull(MemoActionId::fromStorageKey))
        },
        benchmarkRootTag = BenchmarkAnchorContract.MEMO_MENU_ROOT,
    )
}
