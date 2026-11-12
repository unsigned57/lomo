package com.lomo.ui.component.menu

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.lomo.ui.component.common.ModalSheetPresentation
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoMenuBottomSheet(
    state: MemoMenuState,
    presentation: ModalSheetPresentation,
    onDismissRequest: () -> Unit,
    actions: ImmutableList<ActionItemUi>,
    modifier: Modifier = Modifier,
    benchmarkRootTag: String? = null,
) {
    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        sheetState = presentation.sheetState,
        shape = ActionSheetTokens.SheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        MemoActionSheet(
            state = state,
            modifier = Modifier.onSizeChanged(presentation::onContentMeasured),
            actions = actions,
            onDismiss = onDismissRequest,
            benchmarkRootTag = benchmarkRootTag,
        )
    }
}
