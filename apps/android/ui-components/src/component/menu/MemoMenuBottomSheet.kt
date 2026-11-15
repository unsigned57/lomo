package com.lomo.ui.component.menu

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.lomo.ui.component.common.LomoModalBottomSheet
import com.lomo.ui.component.common.ModalSheetPresentation
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoMenuBottomSheet(
    presentation: ModalSheetPresentation,
    onDismissRequest: () -> Unit,
    actions: ImmutableList<ActionItemUi>,
    modifier: Modifier = Modifier,
    state: MemoMenuState? = null,
    benchmarkRootTag: String? = null,
) {
    LomoModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        sheetState = presentation.sheetState,
    ) {
        MemoActionSheet(
            modifier = Modifier.onSizeChanged(presentation::onContentMeasured),
            actions = actions,
            onDismiss = onDismissRequest,
            state = state,
            benchmarkRootTag = benchmarkRootTag,
        )
    }
}
