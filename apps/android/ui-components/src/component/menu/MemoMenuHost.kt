package com.lomo.ui.component.menu

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.lomo.ui.component.common.ModalSheetPresentation
import com.lomo.ui.component.common.rememberModalSheetPresentation
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlin.jvm.JvmInline

@JvmInline
value class MemoMenuItemId(val value: String)

data class MemoMenuState(
    val wordCount: Int = 0,
    val createdTime: String = "",
    val content: String = "",
    val memoId: MemoMenuItemId? = null,
    val imageUrls: List<String> = emptyList(),
) {
    companion object {
        fun withId(
            memoId: MemoMenuItemId,
            wordCount: Int = 0,
            createdTime: String = "",
            content: String = "",
        ): MemoMenuState = MemoMenuState(
            wordCount = wordCount,
            createdTime = createdTime,
            content = content,
            memoId = memoId,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoMenuHost(
    actions: @Composable (state: MemoMenuState, lifecycle: MemoMenuActionLifecycle) -> ImmutableList<ActionItemUi>,
    content: @Composable (showMenu: (MemoMenuState) -> Unit) -> Unit,
    onMenuCleared: () -> Unit,
    benchmarkRootTag: String? = null,
) {
    var activeState by remember { mutableStateOf<MemoMenuState?>(null) }
    val scope = rememberCoroutineScope()
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    content { state ->
        haptic.medium()
        activeState = state
    }

    val current = activeState
    if (current != null) {
        val presentation = rememberModalSheetPresentation()
        MemoMenuBottomSheetHost(
            current = current,
            params =
            MemoMenuBottomSheetParams(
                presentation = presentation,
                scope = scope,
                activeStateProvider = { activeState },
                clearActiveState = {
                    activeState = null
                    onMenuCleared()
                },
                benchmarkRootTag = benchmarkRootTag,
                actions = actions,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private data class MemoMenuBottomSheetParams(
    val presentation: ModalSheetPresentation,
    val scope: CoroutineScope,
    val activeStateProvider: () -> MemoMenuState?,
    val clearActiveState: () -> Unit,
    val benchmarkRootTag: String?,
    val actions: @Composable (state: MemoMenuState, lifecycle: MemoMenuActionLifecycle) -> ImmutableList<ActionItemUi>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoMenuBottomSheetHost(current: MemoMenuState, params: MemoMenuBottomSheetParams) {
    val lifecycle =
        remember(
            params.scope,
            params.presentation.sheetState,
            params.activeStateProvider,
            params.clearActiveState,
        ) {
            MemoMenuActionLifecycle(
                scope = params.scope,
                sheetState = params.presentation.sheetState,
                activeStateProvider = params.activeStateProvider,
                clearActiveState = params.clearActiveState,
            )
        }
    MemoMenuBottomSheet(
        state = current,
        presentation = params.presentation,
        onDismissRequest = params.clearActiveState,
        actions = params.actions(current, lifecycle),
        benchmarkRootTag = params.benchmarkRootTag,
    )
}
