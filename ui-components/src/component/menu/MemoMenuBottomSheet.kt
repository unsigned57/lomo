package com.lomo.ui.component.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.lomo.ui.theme.SheetHandleTokens
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoMenuBottomSheet(
    state: MemoMenuState,
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    actions: ImmutableList<ActionItemUi>,
    modifier: Modifier = Modifier,
    actionAutoReorderEnabled: Boolean = true,
    onActionInvoked: (String) -> Unit = {},
    onActionOrderChanged: (List<String>) -> Unit = {},
    benchmarkRootTag: String? = null,
) {
    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = {
            Box(
                modifier =
                    Modifier
                        .padding(vertical = SheetHandleTokens.VerticalPadding)
                        .width(SheetHandleTokens.Width)
                        .size(SheetHandleTokens.Width, SheetHandleTokens.Height)
                        .clip(SheetHandleTokens.Shape)
                        .background(SheetHandleTokens.color(MaterialTheme.colorScheme)),
            )
        },
    ) {
        MemoActionSheet(
            state = state,
            actions = actions,
            onDismiss = onDismissRequest,
            actionAutoReorderEnabled = actionAutoReorderEnabled,
            onActionInvoked = onActionInvoked,
            onActionOrderChanged = onActionOrderChanged,
            benchmarkRootTag = benchmarkRootTag,
        )
    }
}
